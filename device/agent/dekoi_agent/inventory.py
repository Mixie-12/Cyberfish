"""What the agent can see on the local network."""

import ipaddress
import re
import socket
import subprocess
from pathlib import Path

from .sources import clean_text, valid_ip

ARP_SCAN = "/usr/sbin/arp-scan"
ARP_SCAN_TIMEOUT_SECONDS = 120
MAX_HOSTS = 1024
MAX_VENDOR = 64
# Never probe more than 1024 addresses, however large the local network is.
MIN_SCAN_PREFIX = 22
ARP_COMPLETE = 0x2
# Any address reached through the default route; no packet is ever sent to it.
ROUTE_PROBE = ("192.0.2.1", 9)
_MAC = re.compile(r"^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")

MAX_NETWORKS = 64
MAX_SEEN_NETWORKS = 32
# Sources of a network (networks.source), the most direct evidence first.
NETWORK_SOURCES = ("interface", "router_advertisement", "dhcp_route", "observed")
PRIVATE_IPV4 = tuple(
    ipaddress.IPv4Network(network) for network in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
)

Network = ipaddress.IPv4Network | ipaddress.IPv6Network
Route = tuple[str, Network, str | None]


def _valid_mac(value: str) -> str | None:
    value = value.lower()
    return value if _MAC.match(value) else None


def local_ip() -> str | None:
    """IPv4 address of the interface that carries the default route."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
        try:
            probe.connect(ROUTE_PROBE)
        except OSError:
            return None
        return probe.getsockname()[0]


def scan_target(
    address: str, route_table: Path = Path("/proc/net/route")
) -> tuple[str, str] | None:
    """Interface and network to scan: the directly connected subnet of address,
    narrowed to at most a /22 around it."""
    host = ipaddress.IPv4Address(address)
    for line in route_table.read_text().splitlines()[1:]:
        fields = line.split()
        interface, destination, gateway, mask = fields[0], fields[1], fields[2], fields[7]
        if int(gateway, 16) != 0 or int(mask, 16) == 0:
            continue
        # /proc/net/route prints addresses in host byte order (little-endian).
        network = ipaddress.IPv4Network(
            (
                int(ipaddress.IPv4Address(bytes.fromhex(destination)[::-1])),
                int(mask, 16).bit_count(),
            )
        )
        if host in network:
            prefix = max(network.prefixlen, MIN_SCAN_PREFIX)
            return interface, str(ipaddress.IPv4Network((int(host), prefix), strict=False))
    return None


def parse_arp_scan(output: str) -> list[dict]:
    """Parse `arp-scan --plain` output: IP, MAC and vendor separated by tabs."""
    hosts = {}
    for line in output.splitlines():
        fields = line.split("\t")
        if len(fields) < 2:
            continue
        ip, mac = valid_ip(fields[0]), _valid_mac(fields[1])
        if ip is None or mac is None:
            continue
        host = {"mac": mac, "ip": ip}
        vendor = clean_text(fields[2])[:MAX_VENDOR] if len(fields) > 2 else ""
        if vendor and not vendor.startswith("(Unknown"):
            host["vendor"] = vendor
        hosts[mac] = host
    return list(hosts.values())[:MAX_HOSTS]


def neighbours(interface: str, network: str, arp_table: Path = Path("/proc/net/arp")) -> list[dict]:
    """Hosts on network that the kernel has a complete neighbour entry for:
    those this device exchanged traffic with, also when they did not answer
    the scan."""
    subnet = ipaddress.IPv4Network(network)
    hosts = []
    for line in arp_table.read_text().splitlines()[1:]:
        fields = line.split()
        if len(fields) < 6 or fields[5] != interface or not int(fields[2], 16) & ARP_COMPLETE:
            continue
        ip, mac = valid_ip(fields[0]), _valid_mac(fields[3])
        if ip and mac and ipaddress.IPv4Address(ip) in subnet:
            hosts.append({"mac": mac, "ip": ip})
    return hosts


def scan_hosts() -> list[dict]:
    """The scan's answers, and the neighbours that did not answer it: phone
    hotspots and Wi-Fi power saving drop or delay broadcast ARP, so a scan
    alone missed devices that had just talked to the DeKoi."""
    address = local_ip()
    target = scan_target(address) if address else None
    if target is None:
        return []
    interface, network = target
    result = subprocess.run(  # noqa: S603 - fixed program, arguments derived from the routing table
        [ARP_SCAN, f"--interface={interface}", "--plain", "--ignoredups", "--retry=2", network],
        capture_output=True,
        text=True,
        timeout=ARP_SCAN_TIMEOUT_SECONDS,
        check=True,
    )
    hosts = {host["mac"]: host for host in neighbours(interface, network)}
    hosts.update({host["mac"]: host for host in parse_arp_scan(result.stdout)})
    return list(hosts.values())[:MAX_HOSTS]


def routed_networks(
    route_table: Path = Path("/proc/net/route"),
    ipv6_route_table: Path = Path("/proc/net/ipv6_route"),
) -> list[dict]:
    """Networks in the routing table of the interfaces that carry a default
    route: their subnets, IPv4 and IPv6, and IPv4 routes through a gateway,
    which on DeKoi can only come from DHCP (classless static routes)."""
    routes = _ipv4_routes(route_table.read_text()) + _ipv6_routes(ipv6_route_table.read_text())
    defaults = {
        (interface, network.version): gateway
        for interface, network, gateway in routes
        if network.prefixlen == 0 and gateway
    }
    networks = []
    for interface, network, gateway in routes:
        default = defaults.get((interface, network.version))
        if default is None or not _is_subnet(network):
            continue
        if gateway is None:
            networks.append({"cidr": str(network), "source": "interface", "gateway": default})
        elif network.version == 4:
            networks.append({"cidr": str(network), "source": "dhcp_route", "gateway": gateway})
    return networks


def _ipv4_routes(table: str) -> list[Route]:
    """/proc/net/route prints addresses in host byte order (little-endian)."""
    routes = []
    for line in table.splitlines()[1:]:
        fields = line.split()
        destination = ipaddress.IPv4Address(bytes.fromhex(fields[1])[::-1])
        gateway = ipaddress.IPv4Address(bytes.fromhex(fields[2])[::-1])
        prefix = int(fields[7], 16).bit_count()
        network = ipaddress.IPv4Network((destination, prefix), strict=False)
        routes.append((fields[0], network, str(gateway) if int(gateway) else None))
    return routes


def _ipv6_routes(table: str) -> list[Route]:
    """/proc/net/ipv6_route: destination, prefix length, source, source
    prefix length, next hop, metric, use and reference counts, flags, device."""
    routes = []
    for line in table.splitlines():
        fields = line.split()
        destination = ipaddress.IPv6Address(bytes.fromhex(fields[0]))
        gateway = ipaddress.IPv6Address(bytes.fromhex(fields[4]))
        network = ipaddress.IPv6Network((destination, int(fields[1], 16)), strict=False)
        routes.append((fields[9], network, str(gateway) if int(gateway) else None))
    return routes


def _is_subnet(network: Network) -> bool:
    """A network devices live in: not a default route, a single host, a
    link-local range or multicast."""
    return (
        0 < network.prefixlen < network.max_prefixlen
        and not network.is_link_local
        and not network.is_multicast
    )


class SeenNetworks:
    """Networks known only from what passes by: prefixes in IPv6 router
    advertisements, and the /24 of every private IPv4 source address. The
    least recently seen are forgotten first."""

    def __init__(self) -> None:
        self._seen: dict[str, dict] = {}

    def advertised(self, prefix: str, router: str) -> None:
        self._add({"cidr": prefix, "source": "router_advertisement", "gateway": router})

    def source_address(self, address: str | None) -> None:
        text = valid_ip(address)
        ip = ipaddress.ip_address(text) if text else None
        if ip is None or ip.version != 4 or not any(ip in private for private in PRIVATE_IPV4):
            return
        network = ipaddress.IPv4Network((ip, 24), strict=False)
        self._add({"cidr": str(network), "source": "observed", "gateway": None})

    def _add(self, network: dict) -> None:
        self._seen.pop(network["cidr"], None)
        self._seen[network["cidr"]] = network
        if len(self._seen) > MAX_SEEN_NETWORKS:
            del self._seen[next(iter(self._seen))]

    def report(self, routed: list[dict]) -> list[dict]:
        """Every network once, under its most direct source. An observed /24
        inside a network known otherwise is that network, not a new one."""
        candidates = sorted(
            routed + list(self._seen.values()),
            key=lambda network: NETWORK_SOURCES.index(network["source"]),
        )
        known = [
            ipaddress.ip_network(network["cidr"])
            for network in candidates
            if network["source"] != "observed"
        ]
        merged: dict[str, dict] = {}
        for network in candidates:
            cidr = ipaddress.ip_network(network["cidr"])
            if network["source"] == "observed" and any(
                cidr.version == other.version and cidr.subnet_of(other) for other in known
            ):
                continue
            merged.setdefault(network["cidr"], network)
        return list(merged.values())[:MAX_NETWORKS]


def mac_for(ip: str, arp_table: Path = Path("/proc/net/arp")) -> str | None:
    """MAC address of an on-link IP from the kernel neighbour table."""
    for line in arp_table.read_text().splitlines()[1:]:
        fields = line.split()
        if len(fields) >= 4 and fields[0] == ip and int(fields[2], 16) & ARP_COMPLETE:
            return _valid_mac(fields[3])
    return None
