from pathlib import Path

from dekoi_agent.inventory import MAX_SEEN_NETWORKS, SeenNetworks, routed_networks

ROUTE = (
    "Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\t\tMTU\tWindow\tIRTT\n"
    # Default route, the home subnet, a DHCP classless static route, a host
    # route to the gateway, link-local, and a container bridge.
    "eth0\t00000000\t0101A8C0\t0003\t0\t0\t100\t00000000\t0\t0\t0\n"
    "eth0\t0001A8C0\t00000000\t0001\t0\t0\t100\t00FFFFFF\t0\t0\t0\n"
    "eth0\t0000140A\tFE01A8C0\t0003\t0\t0\t100\t0000FFFF\t0\t0\t0\n"
    "eth0\t0101A8C0\t00000000\t0005\t0\t0\t100\tFFFFFFFF\t0\t0\t0\n"
    "eth0\t0000FEA9\t00000000\t0001\t0\t0\t1000\t0000FFFF\t0\t0\t0\n"
    "podman0\t0000580A\t00000000\t0001\t0\t0\t0\t0000FFFF\t0\t0\t0\n"
)
ZERO = "0" * 32
IPV6_ROUTE = "".join(
    f"{destination} {length} {ZERO} 00 {next_hop} 00000100 00000001 00000000 00000001 {device}\n"
    for destination, length, next_hop, device in [
        ("20010db8000100020000000000000000", "40", ZERO, "eth0"),
        ("fe800000000000000000000000000000", "40", ZERO, "eth0"),
        (ZERO, "00", "fe800000000000000000000000000001", "eth0"),
        ("20010db8000100020000000000000014", "80", ZERO, "eth0"),
        ("ff000000000000000000000000000000", "08", ZERO, "eth0"),
        ("00000000000000000000000000000001", "80", ZERO, "lo"),
    ]
)


def tables(tmp_path: Path) -> tuple[Path, Path]:
    (tmp_path / "route").write_text(ROUTE)
    (tmp_path / "ipv6_route").write_text(IPV6_ROUTE)
    return tmp_path / "route", tmp_path / "ipv6_route"


def test_routed_networks_are_the_subnets_and_routes_of_the_lan(tmp_path: Path) -> None:
    assert routed_networks(*tables(tmp_path)) == [
        {"cidr": "192.168.1.0/24", "source": "interface", "gateway": "192.168.1.1"},
        {"cidr": "10.20.0.0/16", "source": "dhcp_route", "gateway": "192.168.1.254"},
        {"cidr": "2001:db8:1:2::/64", "source": "interface", "gateway": "fe80::1"},
    ]


def test_each_network_is_reported_once_under_its_most_direct_source(tmp_path: Path) -> None:
    seen = SeenNetworks()
    seen.advertised("2001:db8:1:2::/64", "fe80::1")
    seen.advertised("2001:db8:99::/64", "fe80::66")
    for address in ("192.168.1.77", "10.20.5.5", "172.16.4.9", "8.8.8.8", "fd00::1", "x", None):
        seen.source_address(address)
    assert seen.report(routed_networks(*tables(tmp_path))) == [
        {"cidr": "192.168.1.0/24", "source": "interface", "gateway": "192.168.1.1"},
        {"cidr": "2001:db8:1:2::/64", "source": "interface", "gateway": "fe80::1"},
        {"cidr": "2001:db8:99::/64", "source": "router_advertisement", "gateway": "fe80::66"},
        {"cidr": "10.20.0.0/16", "source": "dhcp_route", "gateway": "192.168.1.254"},
        {"cidr": "172.16.4.0/24", "source": "observed", "gateway": None},
    ]


def test_seen_networks_are_bounded_and_keep_the_latest() -> None:
    seen = SeenNetworks()
    for n in range(MAX_SEEN_NETWORKS + 8):
        seen.source_address(f"10.0.{n}.1")
    seen.source_address("10.0.0.2")
    report = [network["cidr"] for network in seen.report([])]
    assert len(report) == MAX_SEEN_NETWORKS
    assert "10.0.0.0/24" in report, "seen again, so remembered again"
    assert "10.0.1.0/24" not in report
