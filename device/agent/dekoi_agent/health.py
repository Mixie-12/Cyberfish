"""Internet health as seen from where DeKoi sits (ADR 0015).

Once a minute: one ICMP echo to the gateway, one lookup of the backend's name
at the network's resolver, and one TCP connection to the backend at the
address that lookup gave. Results are kept per hour and target and reported
as hourly aggregates (device_health_v1). At night, while the owner has it
on, one download from a fixed endpoint measures the connection's speed.

No probe needs anything new: the echo uses the raw socket CAP_NET_RAW
already allows, and every answer is an answer to a connection the agent
opened, which the firewall lets in.
"""

import http.client
import secrets
import select
import socket
import ssl
import statistics
import struct
import time

from . import dns_check
from .lan import DNS_A
from .sources import iso_time

GATEWAY, INTERNET, RESOLVER = "gateway", "internet", "resolver"
PROBE_TIMEOUT_SECONDS = 2.0
HOUR_SECONDS = 3600
# The bounds of health_samples.probes and of a device_health_v1 report.
MAX_PROBES = 60
MAX_SAMPLES = 216
# The backend refuses samples older than 7 days; leave a day for clocks.
MAX_SAMPLE_AGE_SECONDS = 6 * 24 * HOUR_SECONDS

ICMP_ECHO_REQUEST = 8
ICMP_ECHO_REPLY = 0
ECHO_PAYLOAD = bytes(32)
MAX_PACKET = 2048
MAX_READS = 64

# One fixed, verified HTTPS endpoint (docs/security.md, "Outbound traffic").
SPEED_TEST_HOST = "speed.cloudflare.com"
SPEED_TEST_BYTES = 25_000_000
SPEED_TEST_PATH = f"/__down?bytes={SPEED_TEST_BYTES}"
SPEED_TEST_SECONDS = 30
SPEED_TEST_READ_TIMEOUT_SECONDS = 10
MIN_SPEED_TEST_BYTES = 1_000_000
MAX_MBPS = 100_000
CHUNK = 64 * 1024
# The device does not know the owner's time zone; 02:00 to 05:00 UTC is
# night in Europe.
SPEED_TEST_HOURS = range(2, 5)


def icmp_echo(identifier: int, sequence: int) -> bytes:
    header = struct.pack("!BBHHH", ICMP_ECHO_REQUEST, 0, 0, identifier, sequence)
    checksum = _checksum(header + ECHO_PAYLOAD)
    return (
        struct.pack("!BBHHH", ICMP_ECHO_REQUEST, 0, checksum, identifier, sequence) + ECHO_PAYLOAD
    )


def _checksum(data: bytes) -> int:
    """The Internet checksum (RFC 1071) of an even number of bytes."""
    total = sum(struct.unpack(f"!{len(data) // 2}H", data))
    total = (total & 0xFFFF) + (total >> 16)
    total += total >> 16
    return ~total & 0xFFFF


def echo_reply(packet: bytes, identifier: int, sequence: int) -> bool:
    """True if an IPv4 packet read from a raw ICMP socket answers our echo."""
    if len(packet) < 20:
        return False
    start = (packet[0] & 0x0F) * 4
    icmp = packet[start : start + 8]
    if len(icmp) < 8:
        return False
    kind, code, _, reply_identifier, reply_sequence = struct.unpack("!BBHHH", icmp)
    return (kind, code, reply_identifier, reply_sequence) == (
        ICMP_ECHO_REPLY,
        0,
        identifier,
        sequence,
    )


def ping(address: str, timeout: float = PROBE_TIMEOUT_SECONDS) -> float | None:
    """Round trip of one ICMP echo in milliseconds, or None without a reply."""
    identifier, sequence = secrets.randbits(16), secrets.randbits(16)
    start = time.monotonic()
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_RAW, socket.IPPROTO_ICMP) as sock:
            sock.sendto(icmp_echo(identifier, sequence), (address, 0))
            for _ in range(MAX_READS):
                remaining = start + timeout - time.monotonic()
                if remaining <= 0 or not select.select([sock], [], [], remaining)[0]:
                    return None
                packet, source = sock.recvfrom(MAX_PACKET)
                if source[0] == address and echo_reply(packet, identifier, sequence):
                    return _milliseconds(start)
    except OSError:
        return None
    return None


def connect(host: str, port: int, timeout: float = PROBE_TIMEOUT_SECONDS) -> float | None:
    """Time to open a TCP connection in milliseconds, or None if it failed."""
    start = time.monotonic()
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return _milliseconds(start)
    except OSError:
        return None


def lookup(resolver: str, name: str) -> tuple[float | None, frozenset[str]]:
    """Time for the resolver to answer and the IPv4 addresses it gave."""
    start = time.monotonic()
    found = dns_check.ask_lan(resolver, name, DNS_A, PROBE_TIMEOUT_SECONDS)
    return (None, frozenset()) if found is None else (_milliseconds(start), found)


def _milliseconds(start: float) -> float:
    return (time.monotonic() - start) * 1000


def aggregate(results: list[float | None], failed_before: bool) -> dict:
    """One hour of one target as health_samples stores it. Results are
    latencies in order, None for a failed probe; a failure next to another
    is an outage minute. failed_before says whether the probe just before
    the hour failed."""
    answered = sorted(latency for latency in results if latency is not None)
    failed = [latency is None for latency in results]
    around = [failed_before, *failed, False]
    return {
        "probes": len(results),
        "lost": failed.count(True),
        "median_ms": round(statistics.median(answered), 1) if answered else None,
        "worst_ms": round(answered[-1], 1) if answered else None,
        "outage_minutes": sum(
            1 for i, down in enumerate(failed) if down and (around[i] or around[i + 2])
        ),
    }


class Health:
    """The minute probes and their hourly aggregates waiting to be reported."""

    def __init__(self, backend_host: str, backend_port: int) -> None:
        self.backend_host = backend_host
        self.backend_port = backend_port
        # Where the backend's name last led; the TCP probe connects there so
        # that a resolver outage does not look like an internet outage.
        self.backend_address: str | None = None
        self._hour: int | None = None
        self._results: dict[str, list[float | None]] = {}
        self._failed_last: dict[str, bool] = {}
        self._closed: list[tuple[int, dict]] = []

    def measure(self, at: float, gateway: str | None, resolver: str | None) -> None:
        if gateway:
            self._record(at, GATEWAY, ping(gateway))
        if resolver:
            latency, addresses = lookup(resolver, self.backend_host)
            self._record(at, RESOLVER, latency)
            if addresses:
                self.backend_address = min(addresses)
        target = self.backend_address or self.backend_host
        self._record(at, INTERNET, connect(target, self.backend_port))

    def samples(self, at: float) -> list[dict]:
        """Every finished hour not yet reported, newest last."""
        return [sample for hour, sample in self._closed if hour > at - MAX_SAMPLE_AGE_SECONDS]

    def reported(self) -> None:
        self._closed.clear()

    def _record(self, at: float, target: str, latency: float | None) -> None:
        hour = int(at // HOUR_SECONDS) * HOUR_SECONDS
        if hour != self._hour:
            self._close()
            self._hour = hour
        results = self._results.setdefault(target, [])
        if len(results) < MAX_PROBES:
            results.append(latency)

    def _close(self) -> None:
        if self._hour is not None:
            for target, results in self._results.items():
                sample = aggregate(results, self._failed_last.get(target, False))
                self._closed.append(
                    (self._hour, {"hour": iso_time(self._hour), "target": target} | sample)
                )
                self._failed_last[target] = results[-1] is None
        self._results = {}
        del self._closed[:-MAX_SAMPLES]


def night(at: float) -> bool:
    return time.gmtime(at).tm_hour in SPEED_TEST_HOURS


def speed_test() -> float | None:
    """Download speed in Mbps from the fixed endpoint, at most 25 MB and 30
    seconds of it, or None if too little arrived to say."""
    connection = http.client.HTTPSConnection(
        SPEED_TEST_HOST,
        timeout=SPEED_TEST_READ_TIMEOUT_SECONDS,
        context=ssl.create_default_context(),
    )
    received = 0
    try:
        connection.request("GET", SPEED_TEST_PATH)
        response = connection.getresponse()
        if response.status != 200:
            return None
        start = time.monotonic()
        while received < SPEED_TEST_BYTES and time.monotonic() - start < SPEED_TEST_SECONDS:
            chunk = response.read(min(CHUNK, SPEED_TEST_BYTES - received))
            if not chunk:
                break
            received += len(chunk)
        elapsed = time.monotonic() - start
    except (OSError, http.client.HTTPException):
        return None
    finally:
        connection.close()
    if received < MIN_SPEED_TEST_BYTES or elapsed <= 0:
        return None
    return min(round(received * 8 / elapsed / 1_000_000, 1), MAX_MBPS)
