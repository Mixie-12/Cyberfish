import re
import stat
from pathlib import Path

from dekoi_agent.decoys import listening_ports, load_bait_users, load_decoys, running_services
from dekoi_agent.inventory import mac_for, neighbours, parse_arp_scan, scan_target
from dekoi_agent.state import MAX_QUEUE, State

CANARY_CONFIG = Path(__file__).parents[2] / "canary" / "opencanary.conf"
PERSONA = Path(__file__).parents[2] / "canary" / "persona" / "persona.json"
MIGRATIONS = Path(__file__).parents[3] / "backend" / "supabase" / "migrations"


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


def test_decoys_are_the_canary_config_and_the_file_share() -> None:
    decoys = load_decoys(CANARY_CONFIG)
    assert decoys == {
        21: "ftp",
        22: "ssh",
        23: "telnet",
        80: "http",
        102: "s7",
        445: "smb",
        502: "modbus",
        1433: "mssql",
        3200: "sap",
        3299: "sap",
        3306: "mysql",
        3389: "rdp",
        3600: "sap",
        5900: "vnc",
        6379: "redis",
        8000: "sap",
        50000: "sap",
    }


def test_the_backend_takes_every_decoy_service_in_a_heartbeat() -> None:
    # The backend refuses a heartbeat with a service it cannot store, and the
    # device then looks offline.
    (migration,) = MIGRATIONS.glob("*_core_schema.sql")
    schema = migration.read_text()
    name = re.search(r"domain private\.service_name as text\s+check \(value ~ '([^']+)'\)", schema)
    limit = re.search(r"services private\.service_name\[\].*\(services\) <= (\d+)", schema)
    assert name is not None and limit is not None
    services = set(load_decoys(CANARY_CONFIG).values())
    assert len(services) <= int(limit[1])
    assert all(re.search(name[1], service) for service in services)


def test_a_banner_decoy_is_named_by_its_service_key(tmp_path: Path) -> None:
    config = tmp_path / "opencanary.conf"
    config.write_text(
        '{"tcpbanner.enabled": true, "tcpbanner_1.enabled": true, "tcpbanner_1.port": 3200,'
        ' "tcpbanner_1.service": "sap", "tcpbanner_2.enabled": true, "tcpbanner_2.port": 9000,'
        ' "tcpbanner_3.enabled": false, "tcpbanner_3.port": 502, "tcpbanner_3.service": "modbus"}'
    )
    assert load_decoys(config) == {445: "smb", 3200: "sap", 9000: "tcpbanner_2"}


def test_bait_users_are_the_persona_names_in_lower_case(tmp_path: Path) -> None:
    assert {"ks-drift", "ks_fibatch"} <= load_bait_users(PERSONA)
    persona = tmp_path / "persona.json"
    persona.write_text('{"name": "x", "bait_users": {"admin_user": "KS-Drift"}}')
    assert load_bait_users(persona) == {"ks-drift"}


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
    # A service with several ports is one decoy.
    assert running_services({21: "sap", 22: "sap", 80: "http"}, ports) == ["sap"]


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


def test_neighbours_are_complete_entries_on_the_scanned_network(tmp_path: Path) -> None:
    # A phone hotspot: the PC talked to the DeKoi but never answered the scan.
    table = tmp_path / "arp"
    table.write_text(
        "IP address       HW type     Flags       HW address            Mask     Device\n"
        "172.20.10.1      0x1         0x2         3c:22:fb:00:00:01     *        wlan0\n"
        "172.20.10.5      0x1         0x2         a4:83:e7:12:34:56     *        wlan0\n"
        "172.20.10.6      0x1         0x0         00:00:00:00:00:00     *        wlan0\n"
        "172.20.10.7      0x1         0x2         a4:83:e7:12:34:57     *        eth0\n"
        "192.168.9.9      0x1         0x2         a4:83:e7:12:34:58     *        wlan0\n"
    )
    assert neighbours("wlan0", "172.20.10.0/28", table) == [
        {"mac": "3c:22:fb:00:00:01", "ip": "172.20.10.1"},
        {"mac": "a4:83:e7:12:34:56", "ip": "172.20.10.5"},
    ]
