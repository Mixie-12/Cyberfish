import json
import os
import struct
from pathlib import Path

import pytest

from dekoi_agent import agent as agent_module
from dekoi_agent import api, monitors, wifi
from dekoi_agent.agent import Agent
from dekoi_agent.config import Config
from dekoi_agent.state import State

CANARY_CONFIG = Path(__file__).parents[2] / "canary" / "opencanary.conf"
PERSONA = Path(__file__).parents[2] / "canary" / "persona" / "persona.json"
CLAIM_CODE = "C" + "0" * 24 + "1"
HOME_NETWORK = {"cidr": "192.168.1.0/24", "source": "interface", "gateway": "192.168.1.1"}
PHONE_MAC = "62:1f:9a:00:00:01"


class FakeBackend:
    """Records calls and answers them from a per-function list of outcomes."""

    def __init__(self) -> None:
        self.calls: list[tuple[str, dict]] = []
        self.outcomes: dict[str, list[object]] = {}
        self.poisoners: list[tuple[str, str]] = []
        self.dhcp_servers: set[str] = {"192.168.1.1"}
        self.lan_answer = frozenset({"192.0.2.10"})
        self.doh_answer = frozenset({"192.0.2.10"})
        self.mappings_read: list[tuple[str | None, str | None]] = []

    def __call__(self, config: Config, function: str, arguments: dict) -> object:
        self.calls.append((function, arguments))
        outcomes = self.outcomes.get(function, [])
        outcome = outcomes.pop(0) if outcomes else None
        if isinstance(outcome, Exception):
            raise outcome
        return outcome

    def called(self, function: str) -> list[dict]:
        return [arguments for name, arguments in self.calls if name == function]


class FakeListeners:
    """Raw sockets need privileges; the tests feed frames directly."""

    def __init__(self) -> None:
        self.frames: list[bytes] = []
        self.discovery: list[bytes] = []
        self.advertisements: list[tuple[str, str, list[str]]] = []

    def arp_frames(self) -> list[bytes]:
        frames, self.frames = self.frames, []
        return frames

    def discovery_frames(self) -> list[bytes]:
        frames, self.discovery = self.discovery, []
        return frames

    def router_advertisements(self) -> list[tuple[str, str, list[str]]]:
        found, self.advertisements = self.advertisements, []
        return found


class Clock:
    def __init__(self) -> None:
        self.now = 1_800_000_000.0

    def __call__(self) -> float:
        return self.now


@pytest.fixture
def config(tmp_path: Path) -> Config:
    (tmp_path / "claim-code").write_text(CLAIM_CODE + "\n")
    (tmp_path / "canary.log").write_text("")
    (tmp_path / "samba.log").write_text("")
    return Config(
        api_url="https://example.invalid",
        api_key="sb_publishable_test",
        agent_version="0.1.0",
        state_path=tmp_path / "state.json",
        claim_code_path=tmp_path / "claim-code",
        canary_log=tmp_path / "canary.log",
        samba_log=tmp_path / "samba.log",
        scan_log=tmp_path / "scan.json",
        ids_socket=tmp_path / "eve.sock",
        decoy_config=CANARY_CONFIG,
        persona=PERSONA,
    )


@pytest.fixture
def backend(monkeypatch: pytest.MonkeyPatch) -> FakeBackend:
    fake = FakeBackend()
    monkeypatch.setattr(api, "call", fake)
    monkeypatch.setattr(agent_module, "listening_ports", lambda: {22, 80})
    monkeypatch.setattr(agent_module.inventory, "local_ip", lambda: "192.168.1.20")
    monkeypatch.setattr(agent_module.inventory, "mac_for", lambda ip: None)
    monkeypatch.setattr(agent_module.inventory, "scan_hosts", lambda: [])
    monkeypatch.setattr(
        agent_module.inventory, "scan_target", lambda address: ("eth0", "192.168.1.0/24")
    )
    monkeypatch.setattr(agent_module.lan, "own_macs", lambda: {"dc:a6:32:00:00:01"})
    monkeypatch.setattr(agent_module.lan, "default_gateway", lambda: "192.168.1.1")
    monkeypatch.setattr(agent_module.lan, "interface_mac", lambda interface: "dc:a6:32:00:00:01")
    monkeypatch.setattr(agent_module.lan, "probe_name_poisoning", lambda: fake.poisoners)
    monkeypatch.setattr(agent_module.lan, "probe_dhcp_servers", lambda mac: fake.dhcp_servers)
    monkeypatch.setattr(agent_module.lan, "ask_for_services", lambda: None)
    monkeypatch.setattr(agent_module.inventory, "routed_networks", lambda: [dict(HOME_NETWORK)])
    monkeypatch.setattr(agent_module.health, "ping", lambda address: 1.5)
    monkeypatch.setattr(agent_module.health, "connect", lambda host, port: 20.0)
    monkeypatch.setattr(agent_module.health, "speed_test", lambda: 93.4)
    monkeypatch.setattr(agent_module.dns_check, "lan_resolver", lambda: "192.168.1.1")
    monkeypatch.setattr(agent_module.dns_check, "ask_lan", lambda *question: fake.lan_answer)
    monkeypatch.setattr(
        agent_module.dns_check, "ask_doh", lambda questions: [fake.doh_answer] * len(questions)
    )

    def port_mappings(url: str | None, gateway: str | None) -> list[dict]:
        fake.mappings_read.append((url, gateway))
        return []

    monkeypatch.setattr(agent_module.upnp, "port_mappings", port_mappings)
    return fake


def ssh_attempt() -> str:
    return json.dumps(
        {
            "logtype": 4002,
            "src_host": "192.168.1.50",
            "dst_port": 22,
            "utc_time": "2026-11-07 10:00:00.000000",
            "logdata": {"USERNAME": "root", "PASSWORD": "toor"},
        }
    )


def test_registration_waits_for_the_claim_and_keeps_its_secret(
    config: Config, backend: FakeBackend
) -> None:
    clock = Clock()
    backend.outcomes["device_register_v1"] = [api.ApiError(403, "refused"), "device-1"]
    agent = Agent(config, State(), clock, FakeListeners())

    agent.tick()
    assert agent.state.device_id is None
    agent.tick()
    assert len(backend.called("device_register_v1")) == 1, "backs off after a refusal"

    clock.now += 60
    agent.tick()
    first, second = backend.called("device_register_v1")
    assert first == second
    assert first["claim_code"] == CLAIM_CODE
    assert len(first["secret"]) == 64
    saved = State.load(config.state_path)
    assert saved.device_id == "device-1"
    assert saved.secret == first["secret"]


def test_events_are_delivered_without_passwords(config: Config, backend: FakeBackend) -> None:
    agent = Agent(config, State(device_id="device-1", secret="a" * 64), Clock(), FakeListeners())
    agent.tick()
    config.canary_log.write_text(ssh_attempt() + "\n")
    agent.tick()

    (call,) = backend.called("device_events_v1")
    (event,) = call["events"]
    assert event["kind"] == "login_attempt"
    assert event["service"] == "ssh"
    assert event["agent_event_id"] == 1
    assert "toor" not in json.dumps(call)
    assert agent.state.queue == []


def test_file_share_events_are_delivered(config: Config, backend: FakeBackend) -> None:
    agent = Agent(config, State(device_id="device-1", secret="a" * 64), Clock(), FakeListeners())
    agent.tick()
    config.samba_log.write_text(
        "  192.168.1.50|Public|/srv/decoy/Public|connect|ok|Public\n"
        "  192.168.1.50|Public|/srv/decoy/Public|pread_recv|ok|/srv/decoy/Public/notes.txt\n"
    )
    agent.tick()

    (call,) = backend.called("device_events_v1")
    assert [(e["kind"], e["service"], e["detail"]) for e in call["events"]] == [
        ("connection", "smb", {"share": "Public"}),
        ("file_access", "smb", {"share": "Public", "file": "notes.txt"}),
    ]
    position = State.load(config.state_path).positions["samba"]
    assert position == [config.samba_log.stat().st_ino, config.samba_log.stat().st_size]


def test_a_sign_in_with_a_bait_name_is_flagged_and_never_counted_as_a_repeat(
    config: Config, backend: FakeBackend
) -> None:
    agent = Agent(config, State(device_id="device-1", secret="a" * 64), Clock(), FakeListeners())
    agent.tick()
    bait = json.loads(ssh_attempt())
    bait["logdata"] = {"USERNAME": "KS-Drift", "PASSWORD": "Fryselager#28"}
    config.canary_log.write_text(ssh_attempt() + "\n" + json.dumps(bait) + "\n")
    agent.tick()

    (call,) = backend.called("device_events_v1")
    assert [e["detail"] for e in call["events"]] == [
        {"username": "root"},
        {"username": "KS-Drift", "bait": True},
    ]
    assert "Fryselager#28" not in json.dumps(call)


def test_backend_outage_keeps_events_and_malformed_batches_are_dropped(
    config: Config, backend: FakeBackend
) -> None:
    clock = Clock()
    backend.outcomes["device_events_v1"] = [api.ApiError(503, "down"), api.ApiError(400, "bad")]
    agent = Agent(config, State(device_id="device-1", secret="a" * 64), clock, FakeListeners())
    agent.tick()
    config.canary_log.write_text(ssh_attempt() + "\n")

    agent.tick()
    assert len(agent.state.queue) == 1
    assert State.load(config.state_path).queue == agent.state.queue

    clock.now += 2
    agent.tick()
    assert agent.state.queue == []
    assert len(backend.called("device_events_v1")) == 2


HEARTBEAT_V1 = {
    "device_id": "device-1",
    "secret": "a" * 64,
    "agent_version": "0.1.0",
    "services": ["http", "ssh"],
    "local_ip": "192.168.1.20",
}


def test_heartbeat_reports_running_decoys_and_monitors(
    config: Config, backend: FakeBackend
) -> None:
    (config.canary_log.parent / "image").write_text("0.1.0 2026-11-02T04:30:00Z\n")
    agent = Agent(config, State(device_id="device-1", secret="a" * 64), Clock(), FakeListeners())
    agent.tick()
    (heartbeat,) = backend.called("device_heartbeat_v2")
    assert heartbeat == HEARTBEAT_V1 | {
        "monitors": [
            {"name": "decoys", "state": "starting", "detail": {"decoys": 2}},
            {"name": "port_scan", "state": "starting"},
            {"name": "arp_watch", "state": "starting"},
            {"name": "dhcp_watch", "state": "starting"},
            {"name": "router_watch", "state": "ok"},
            {"name": "name_poisoning", "state": "starting"},
            {"name": "ids", "state": "starting"},
            {"name": "inventory", "state": "starting"},
        ],
        "images": [{"name": "canary", "version": "0.1.0", "updated_at": "2026-11-02T04:30:00Z"}],
    }
    assert backend.called("device_heartbeat_v1") == []


def test_monitors_follow_their_sources(config: Config, backend: FakeBackend) -> None:
    listeners = FakeListeners()
    clock = Clock()
    agent = Agent(config, State(device_id="device-1", secret="a" * 64), clock, listeners)
    agent.tick()
    config.scan_log.write_text("")
    listeners.frames = [b"\x00" * 42]
    clock.now += 60
    agent.tick()

    states = {m["name"]: m["state"] for m in backend.called("device_heartbeat_v2")[-1]["monitors"]}
    assert states == {
        "decoys": "starting",
        "port_scan": "ok",
        "arp_watch": "ok",
        "dhcp_watch": "ok",
        "router_watch": "ok",
        "name_poisoning": "ok",
        "ids": "starting",
        "inventory": "ok",
    }


@pytest.mark.parametrize("status", [404, 400])
def test_heartbeat_falls_back_to_v1(config: Config, backend: FakeBackend, status: int) -> None:
    """An older backend (404) or a refused report (400) still keeps the
    device online."""
    backend.outcomes["device_heartbeat_v2"] = [api.ApiError(status, "no")]
    agent = Agent(config, State(device_id="device-1", secret="a" * 64), Clock(), FakeListeners())
    agent.tick()
    assert backend.called("device_heartbeat_v1") == [HEARTBEAT_V1]
    assert agent.heartbeat_schedule.delay == agent_module.HEARTBEAT_SECONDS


def test_heartbeat_outage_is_retried_later(config: Config, backend: FakeBackend) -> None:
    backend.outcomes["device_heartbeat_v2"] = [api.ApiError(503, "down")]
    agent = Agent(config, State(device_id="device-1", secret="a" * 64), Clock(), FakeListeners())
    agent.tick()
    assert backend.called("device_heartbeat_v1") == []
    assert agent.heartbeat_schedule.delay > agent_module.HEARTBEAT_SECONDS


@pytest.mark.parametrize("error", [api.ApiError(0, "unreachable"), api.ApiError(401, "bad key")])
def test_a_registered_agent_is_ready_only_after_a_heartbeat(
    config: Config, backend: FakeBackend, error: api.ApiError
) -> None:
    """An updated image that cannot reach the backend never reports ready,
    so podman auto-update rolls it back."""
    clock = Clock()
    backend.outcomes["device_heartbeat_v2"] = [error]
    agent = Agent(config, State(device_id="device-1", secret="a" * 64), clock, FakeListeners())
    agent.tick()
    assert not agent.ready

    clock.now += agent.heartbeat_schedule.delay
    agent.tick()
    assert agent.ready


def test_an_agent_waiting_to_be_claimed_is_ready_at_once(
    config: Config, backend: FakeBackend
) -> None:
    backend.outcomes["device_register_v1"] = [api.ApiError(403, "refused")]
    agent = Agent(config, State(), Clock(), FakeListeners())
    agent.tick()
    assert agent.ready


def test_monitors_start_again_when_the_device_registers(
    config: Config, backend: FakeBackend
) -> None:
    """A card claimed long after it first started does not report its
    probes and inventory as failed before they have run."""
    clock = Clock()
    backend.outcomes["device_register_v1"] = [api.ApiError(403, "refused"), "device-1"]
    agent = Agent(config, State(), clock, FakeListeners())
    agent.tick()
    clock.now += monitors.STARTUP_SECONDS + 60
    agent.tick()
    agent.tick()

    (heartbeat,) = backend.called("device_heartbeat_v2")
    states = {m["name"]: m["state"] for m in heartbeat["monitors"]}
    assert states["inventory"] == states["dhcp_watch"] == states["name_poisoning"] == "starting"


def test_network_findings_are_delivered(config: Config, backend: FakeBackend) -> None:
    listeners = FakeListeners()
    clock = Clock()
    agent = Agent(config, State(device_id="device-1", secret="a" * 64), clock, listeners)
    agent.tick()
    assert agent.state.dhcp_servers == ["192.168.1.1"], "the first DHCP server is learned"

    listeners.advertisements = [("fe80::1", "aa:bb:cc:00:00:01", [])]
    agent.tick()
    assert agent.state.routers == ["aa:bb:cc:00:00:01"], "the first router is learned"

    backend.poisoners = [("192.168.1.66", "llmnr")]
    backend.dhcp_servers = {"192.168.1.1", "192.168.1.66"}
    listeners.advertisements = [("fe80::66", "3c:22:fb:00:00:66", [])]
    clock.now += 15 * 60
    agent.tick()
    clock.now += 1
    agent.tick()

    kinds = [e["kind"] for call in backend.called("device_events_v1") for e in call["events"]]
    assert sorted(kinds) == ["name_poisoning", "rogue_dhcp", "rogue_router"]


def dhcp_request_frame(mac: str, host_name: bytes) -> bytes:
    raw_mac = bytes.fromhex(mac.replace(":", ""))
    bootp = struct.pack("!BBBBIHH", 1, 1, 6, 0, 7, 0, 0) + b"\x00" * 16
    bootp += raw_mac.ljust(16, b"\x00") + b"\x00" * 192 + b"\x63\x82\x53\x63"
    bootp += bytes([53, 1, 3, 12, len(host_name)]) + host_name + b"\xff"
    udp = struct.pack("!HHHH", 68, 67, 8 + len(bootp), 0) + bootp
    ip = struct.pack(
        "!BBHHHBBH4s4s", 0x45, 0, 20 + len(udp), 0, 0, 64, 17, 0, b"\0" * 4, b"\xff" * 4
    )
    return b"\xff" * 6 + raw_mac + b"\x08\x00" + ip + udp


def test_inventory_reports_identified_hosts_and_networks(
    config: Config, backend: FakeBackend, monkeypatch: pytest.MonkeyPatch
) -> None:
    listeners = FakeListeners()
    listeners.discovery = [
        dhcp_request_frame(PHONE_MAC, b"Karis-iPhone"),
        dhcp_request_frame("dc:a6:32:00:00:01", b"our-own-probe"),
    ]
    listeners.advertisements = [("fe80::1", "aa:bb:cc:00:00:01", ["2001:db8:1:2::/64"])]
    monkeypatch.setattr(
        agent_module.inventory,
        "scan_hosts",
        lambda: [
            {"mac": PHONE_MAC, "ip": "192.168.1.60"},
            {"mac": "dc:a6:32:00:00:01", "ip": "192.168.1.20"},
        ],
    )
    agent = Agent(config, State(device_id="device-1", secret="a" * 64), Clock(), listeners)
    agent.tick()

    (call,) = backend.called("device_hosts_v2")
    assert call["hosts"] == [
        {
            "mac": PHONE_MAC,
            "ip": "192.168.1.60",
            "kind": "phone",
            "os": "ios",
            "hostname": "Karis-iPhone",
        },
        {"mac": "dc:a6:32:00:00:01", "ip": "192.168.1.20", "kind": "unknown"},
    ]
    assert call["networks"] == [
        HOME_NETWORK,
        {"cidr": "2001:db8:1:2::/64", "source": "router_advertisement", "gateway": "fe80::1"},
    ]
    assert backend.called("device_hosts_v1") == []


def ssdp_answer_frame(source_ip: str, payload: bytes) -> bytes:
    udp = struct.pack("!HHHH", 1900, 50123, 8 + len(payload), 0) + payload
    ip = struct.pack(
        "!BBHHHBBH4s4s",
        0x45,
        0,
        20 + len(udp),
        0,
        0,
        64,
        17,
        0,
        bytes(map(int, source_ip.split("."))),
        bytes([192, 168, 1, 20]),
    )
    return bytes.fromhex("dca632000001001122334455") + bytes.fromhex("0800") + ip + udp


def test_network_health_is_reported_every_hour(config: Config, backend: FakeBackend) -> None:
    listeners = FakeListeners()
    clock = Clock()
    clock.now += 17 * 3600  # 01:00 UTC
    igd = (
        b"HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n"
        b"LOCATION: http://192.168.1.1:5000/rootDesc.xml\r\n\r\n"
    )
    listeners.discovery = [
        ssdp_answer_frame("192.168.1.66", igd.replace(b"192.168.1.1:", b"192.168.1.66:")),
        ssdp_answer_frame("192.168.1.1", igd),
    ]
    backend.outcomes["device_health_v1"] = [{"speed_test": True}, {"speed_test": False}]
    agent = Agent(config, State(device_id="device-1", secret="a" * 64), clock, listeners)

    agent.tick()
    (first,) = backend.called("device_health_v1")
    assert first["samples"] == [], "no hour has ended yet"
    assert (first["dns"], first["resolver"]) == ("ok", "192.168.1.1")
    assert first["download_mbps"] is None, "not yet asked for"
    assert backend.mappings_read == [("http://192.168.1.1:5000/rootDesc.xml", "192.168.1.1")]

    for _ in range(60):
        clock.now += 60
        agent.tick()
    first, second = backend.called("device_health_v1")
    assert [(s["target"], s["probes"], s["lost"]) for s in second["samples"]] == [
        ("gateway", 60, 0),
        ("resolver", 60, 0),
        ("internet", 60, 0),
    ]
    assert second["download_mbps"] == 93.4, "asked for, and it is night"
    assert agent.speed_test_wanted is False

    backend.outcomes["device_health_v1"] = [api.ApiError(503, "down")]
    clock.now += 3600
    agent.tick()
    clock.now += 60
    agent.tick()
    assert len(backend.called("device_health_v1")) == 3, "not retried before the next hour"
    assert len(agent.health.samples(clock.now)) == 3, "kept for the next report"


def test_wifi_watch_reports_its_state_and_queues_what_it_finds(
    config: Config, backend: FakeBackend, tmp_path: Path
) -> None:
    clock = Clock()
    agent = Agent(config, State(device_id="device-1", secret="a" * 64), clock, FakeListeners())
    scan = tmp_path / "wifi" / "scan"
    country = tmp_path / "wifi-country" / "country"
    scan.parent.mkdir()
    country.parent.mkdir()
    agent.wifi = wifi.Watch(tmp_path / "wifi.json", scan, country)
    # Asked for scans a moment ago; the host has not scanned yet.
    country.write_text("NO\n")
    os.utime(country, (clock.now, clock.now))
    settings = {"name": "Home", "country": "NO"}
    backend.outcomes["device_wifi_v1"] = [settings]
    agent.tick()
    (call,) = backend.called("device_wifi_v1")
    assert call == {"device_id": "device-1", "secret": "a" * 64, "watch": "no_name"}
    assert country.read_text() == "NO\n", "the host is still asked to scan"

    clock.now += wifi.CHECK_SECONDS
    agent.wifi.learned.since = clock.now - wifi.LEARNING_SECONDS
    scan.write_text(
        "scan\nBSS 02:00:00:00:00:66(on wlan0)\n\tfreq: 2437\n"
        "\tcapability: ESS ShortSlotTime (0x0401)\n\tsignal: -40.00 dBm\n\tSSID: Home\n"
    )
    os.utime(scan, (clock.now, clock.now))
    backend.outcomes["device_wifi_v1"] = [settings]
    agent.tick()
    assert backend.called("device_wifi_v1")[-1]["watch"] == "learning"
    assert [event["kind"] for event in agent.state.queue] == ["evil_twin"]
