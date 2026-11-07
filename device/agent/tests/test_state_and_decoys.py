import stat
from pathlib import Path

from dekoi_agent.decoys import listening_ports, load_decoys, running_services
from dekoi_agent.inventory import mac_for, parse_arp_scan, scan_target
from dekoi_agent.state import MAX_QUEUE, State

CANARY_CONFIG = Path(__file__).parents[2] / "canary" / "opencanary.conf"


def test_state_round_trip_is_private(tmp_path: Path) -> None:
    path = tmp_path / "state.json"
    state = State(device_id="d", secret="s" * 64)
    state.enqueue([{"kind": "connection"}])
    state.save(path)
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    assert State.load(path) == state


def test_missing_state_is_empty(tmp_path: Path) -> None:
    assert State.load(tmp_path / "state.json") == State()


def test_enqueue_numbers_events_and_bounds_the_queue() -> None:
    state = State()
    dropped = state.enqueue([{"n": i} for i in range(MAX_QUEUE + 3)])
    assert dropped == 3
    assert len(state.queue) == MAX_QUEUE
    assert state.queue[0] == {"n": 3, "agent_event_id": 4}
    assert state.next_event_id == MAX_QUEUE + 4


def test_decoys_come_from_the_canary_config() -> None:
    decoys = load_decoys(CANARY_CONFIG)
    assert decoys == {
        21: "ftp",
        22: "ssh",
        23: "telnet",
        80: "http",
        3306: "mysql",
        3389: "rdp",
        5900: "vnc",
        6379: "redis",
    }


def test_listening_ports_and_running_services(tmp_path: Path) -> None:
    header = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid\n"
    (tmp_path / "tcp").write_text(
        header
        + "   0: 00000000:0016 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0\n"
        + "   1: 0100007F:0050 0100007F:9C40 01 00000000:00000000 00:00000000 00000000     0\n"
    )
    (tmp_path / "tcp6").write_text(
        header
        + "   0: 00000000000000000000000000000000:0015 00000000000000000000000000000000:0000 0A"
        + " 00000000:00000000 00:00000000 00000000     0\n"
    )
    ports = listening_ports(tmp_path)
    assert ports == {22, 21}
    assert running_services({21: "ftp", 22: "ssh", 80: "http"}, ports) == ["ftp", "ssh"]


def test_parse_arp_scan() -> None:
    output = (
        "192.168.1.1\t3C:22:FB:00:00:01\tApple, Inc.\n"
        "192.168.1.7\t02:11:22:33:44:55\t(Unknown: locally administered)\n"
        "192.168.1.7\t02:11:22:33:44:55\t(Unknown: locally administered)\n"
        "garbage line\n"
        "192.168.1.9\tnot-a-mac\tVendor\n"
    )
    assert parse_arp_scan(output) == [
        {"mac": "3c:22:fb:00:00:01", "ip": "192.168.1.1", "vendor": "Apple, Inc."},
        {"mac": "02:11:22:33:44:55", "ip": "192.168.1.7"},
    ]


ROUTE_HEADER = (
    "Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\t\tMTU\tWindow\tIRTT\n"
)


def test_scan_target_is_the_connected_home_subnet(tmp_path: Path) -> None:
    table = tmp_path / "route"
    table.write_text(
        ROUTE_HEADER
        + "eth0\t00000000\t0101A8C0\t0003\t0\t0\t100\t00000000\t0\t0\t0\n"
        + "eth0\t0001A8C0\t00000000\t0001\t0\t0\t100\t00FFFFFF\t0\t0\t0\n"
    )
    assert scan_target("192.168.1.20", table) == ("eth0", "192.168.1.0/24")


def test_scan_target_is_capped_on_large_networks(tmp_path: Path) -> None:
    table = tmp_path / "route"
    table.write_text(ROUTE_HEADER + "eth0\t0000010A\t00000000\t0001\t0\t0\t0\t0000FFFF\t0\t0\t0\n")
    assert scan_target("10.1.7.4", table) == ("eth0", "10.1.4.0/22")
    assert scan_target("172.16.0.1", table) is None


def test_mac_for_uses_complete_neighbour_entries(tmp_path: Path) -> None:
    table = tmp_path / "arp"
    table.write_text(
        "IP address       HW type     Flags       HW address            Mask     Device\n"
        "192.168.1.50     0x1         0x2         3c:22:fb:00:00:01     *        eth0\n"
        "192.168.1.51     0x1         0x0         00:00:00:00:00:00     *        eth0\n"
    )
    assert mac_for("192.168.1.50", table) == "3c:22:fb:00:00:01"
    assert mac_for("192.168.1.51", table) is None
    assert mac_for("192.168.1.99", table) is None
