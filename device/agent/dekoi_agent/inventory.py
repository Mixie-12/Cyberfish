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


def scan_hosts() -> list[dict]:
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
    return parse_arp_scan(result.stdout)


def mac_for(ip: str, arp_table: Path = Path("/proc/net/arp")) -> str | None:
    """MAC address of an on-link IP from the kernel neighbour table."""
    for line in arp_table.read_text().splitlines()[1:]:
        fields = line.split()
        if len(fields) >= 4 and fields[0] == ip and int(fields[2], 16) & ARP_COMPLETE:
            return _valid_mac(fields[3])
    return None
