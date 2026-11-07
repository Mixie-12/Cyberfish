import math
import struct
from typing import ClassVar

import pytest

from dekoi_agent import health
from dekoi_agent.health import Health, aggregate, echo_reply, icmp_echo, night
from dekoi_agent.sources import iso_time

HOUR = 3600
# 2027-01-15 08:00:00 UTC, the start of an hour.
START = 1_800_000_000.0


def ipv4(payload: bytes, header_words: int = 5) -> bytes:
    header = struct.pack(
        "!BBHHHBBH4s4s", 0x40 | header_words, 0, 0, 0, 0, 64, 1, 0, b"\0" * 4, b"\0" * 4
    )
    return header + bytes(4 * (header_words - 5)) + payload


def test_echo_requests_carry_a_valid_checksum_and_only_our_reply_counts() -> None:
    request = icmp_echo(0x1234, 7)
    words = struct.unpack(f"!{len(request) // 2}H", request)
    total = sum(words)
    while total >> 16:
        total = (total & 0xFFFF) + (total >> 16)
    assert total == 0xFFFF, "the checksum makes the one's complement sum all ones"

    answer = bytes([0]) + request[1:]
    assert echo_reply(ipv4(answer), 0x1234, 7)
    assert echo_reply(ipv4(answer, header_words=6), 0x1234, 7), "IP options are skipped"
    assert not echo_reply(ipv4(request), 0x1234, 7), "our own request"
    assert not echo_reply(ipv4(answer), 0x1234, 8)
    assert not echo_reply(ipv4(answer), 0x4321, 7)
    assert not echo_reply(ipv4(answer[:6]), 0x1234, 7)
    assert not echo_reply(b"\x45", 0x1234, 7)


def test_an_hour_is_aggregated_with_outages_as_runs_of_failures() -> None:
    assert aggregate([1.0, 3.0, 2.0, 10.0], failed_before=False) == {
        "probes": 4,
        "lost": 0,
        "median_ms": 2.5,
        "worst_ms": 10.0,
        "outage_minutes": 0,
    }
    # A single failure is loss, not an outage; two in a row are.
    sample = aggregate([1.0, None, 1.0, None, None, None, 1.0], failed_before=False)
    assert (sample["lost"], sample["outage_minutes"]) == (4, 3)
    assert aggregate([None, 1.0], failed_before=True)["outage_minutes"] == 1
    assert aggregate([None, None], failed_before=False) == {
        "probes": 2,
        "lost": 2,
        "median_ms": None,
        "worst_ms": None,
        "outage_minutes": 2,
    }


def probes(
    monkeypatch: pytest.MonkeyPatch,
    ping: float | None = 1.0,
    lookup: tuple[float | None, frozenset[str]] = (5.0, frozenset({"203.0.113.10"})),
    connect: float | None = 20.0,
) -> list[tuple[str, int]]:
    """Fakes the three probes; returns where the internet probe connected."""
    connected: list[tuple[str, int]] = []

    def fake_connect(host: str, port: int) -> float | None:
        connected.append((host, port))
        return connect

    monkeypatch.setattr(health, "ping", lambda address: ping)
    monkeypatch.setattr(health, "lookup", lambda resolver, name: lookup)
    monkeypatch.setattr(health, "connect", fake_connect)
    return connected


def test_finished_hours_are_reported_once(monkeypatch: pytest.MonkeyPatch) -> None:
    connected = probes(monkeypatch)
    monitor = Health("api.example.org", 443)
    for minute in range(60):
        monitor.measure(START + 60 * minute, "192.168.1.1", "192.168.1.1")
    assert monitor.samples(START + HOUR) == [], "the hour is not over"
    assert connected[0] == ("203.0.113.10", 443), "the address the resolver gave"

    monitor.measure(START + HOUR, "192.168.1.1", "192.168.1.1")
    samples = monitor.samples(START + HOUR)
    assert [(s["hour"], s["target"], s["probes"]) for s in samples] == [
        ("2027-01-15T08:00:00+00:00", "gateway", 60),
        ("2027-01-15T08:00:00+00:00", "resolver", 60),
        ("2027-01-15T08:00:00+00:00", "internet", 60),
    ]
    monitor.reported()
    assert monitor.samples(START + HOUR) == []


def test_without_an_answer_from_the_resolver_the_backend_is_reached_by_name(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    connected = probes(monkeypatch, lookup=(None, frozenset()))
    monitor = Health("api.example.org", 443)
    monitor.measure(START, None, "192.168.1.1")
    monitor.measure(START + HOUR, None, None)
    assert connected == [("api.example.org", 443)] * 2
    assert {s["target"] for s in monitor.samples(START + HOUR)} == {"resolver", "internet"}


def test_reports_stay_within_what_the_backend_accepts(monkeypatch: pytest.MonkeyPatch) -> None:
    probes(monkeypatch)
    monitor = Health("api.example.org", 443)
    for second in range(0, 2 * HOUR, 30):
        monitor.measure(START + second, "192.168.1.1", None)
    monitor.measure(START + 2 * HOUR, "192.168.1.1", None)
    assert {s["probes"] for s in monitor.samples(START)} == {health.MAX_PROBES}

    for hour in range(3, 120):
        monitor.measure(START + hour * HOUR, "192.168.1.1", "192.168.1.1")
    at = START + 120 * HOUR
    assert len(monitor.samples(at)) == health.MAX_SAMPLES
    later = at + 5 * 24 * HOUR
    cutoff = iso_time(later - health.MAX_SAMPLE_AGE_SECONDS)
    assert monitor.samples(later)
    assert all(s["hour"] > cutoff for s in monitor.samples(later)), "older hours are left out"


def test_the_speed_test_runs_only_at_night() -> None:
    assert not night(START)
    assert night(START + 18 * HOUR)  # 02:00 UTC
    assert night(START + 20 * HOUR + 59 * 60)  # 04:59 UTC
    assert not night(START + 21 * HOUR)


class FakeDownload:
    """An HTTPS connection whose response is size bytes, read in chunks."""

    status = 200
    size = 0
    requested: ClassVar[list[tuple[str, str]]] = []

    def __init__(self, host: str, timeout: float, context: object) -> None:
        self.host = host
        self.left = FakeDownload.size

    def request(self, method: str, path: str) -> None:
        FakeDownload.requested.append((self.host, path))

    def getresponse(self) -> "FakeDownload":
        return self

    def read(self, limit: int) -> bytes:
        chunk = min(limit, self.left)
        self.left -= chunk
        return bytes(chunk)

    def close(self) -> None:
        pass


def test_the_speed_test_downloads_at_most_25_mb_from_the_fixed_endpoint(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    ticks = iter(range(1000))
    monkeypatch.setattr(health.http.client, "HTTPSConnection", FakeDownload)
    monkeypatch.setattr(health.time, "monotonic", lambda: next(ticks) / 100)
    monkeypatch.setattr(FakeDownload, "requested", [])
    monkeypatch.setattr(FakeDownload, "size", 100_000_000)

    mbps = health.speed_test()

    assert FakeDownload.requested == [("speed.cloudflare.com", "/__down?bytes=25000000")]
    reads = math.ceil(health.SPEED_TEST_BYTES / health.CHUNK)
    assert mbps == round(health.SPEED_TEST_BYTES * 8 / ((reads + 1) / 100) / 1e6, 1)

    monkeypatch.setattr(FakeDownload, "size", 100_000)
    assert health.speed_test() is None, "too little to say"
    monkeypatch.setattr(FakeDownload, "status", 503)
    assert health.speed_test() is None
