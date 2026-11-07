"""What the agent can see on the local network."""

import re
import socket
import subprocess
from pathlib import Path
from urllib.parse import urlsplit

from .sources import clean_text, valid_ip

ARP_SCAN = "/usr/sbin/arp-scan"
ARP_SCAN_TIMEOUT_SECONDS = 120
MAX_HOSTS = 1024
MAX_VENDOR = 64
ARP_COMPLETE = 0x2
_MAC = re.compile(r"^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")


def _valid_mac(value: str) -> str | None:
    value = value.lower()
    return value if _MAC.match(value) else None


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
    result = subprocess.run(  # noqa: S603 - fixed arguments, no shell, no external input
        [ARP_SCAN, "--localnet", "--plain", "--ignoredups", "--retry=2"],
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


def local_ip(api_url: str) -> str | None:
    """Address of the interface that routes to the backend. Sends nothing."""
    parts = urlsplit(api_url)
    port = parts.port or (443 if parts.scheme == "https" else 80)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
        try:
            probe.connect((parts.hostname, port))
        except OSError:
            return None
        return probe.getsockname()[0]
