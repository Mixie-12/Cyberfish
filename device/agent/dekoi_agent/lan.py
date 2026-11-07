"""Watching the local network beyond the decoys (ADR 0003).

A device on a switched network sees broadcast and multicast frames and the
answers to its own questions. That is enough to notice:

- network sweeps: one host asking "who has" for many addresses (ARP);
- ARP spoofing: an address claimed by a second hardware address while the
  first is still in use, the classic man-in-the-middle setup;
- rogue DHCP servers and rogue IPv6 routers: a new one appearing after the
  legitimate one has been learned;
- name poisoning (LLMNR, mDNS, NBT-NS): we ask for a name that does not exist;
  any answer means a poisoner such as Responder.

Parsing and detection are pure functions; the socket code is at the bottom.
"""

import ipaddress
import os
import random
import secrets
import select
import socket
import struct
import time

from .sources import iso_time

SWEEP_WINDOW_SECONDS = 60
SWEEP_MIN_TARGETS = 32
BINDING_ALIVE_SECONDS = 5 * 60
GATEWAY_BINDING_ALIVE_SECONDS = 24 * 60 * 60
MAX_TRACKED = 4096
PROBE_WAIT_SECONDS = 3.0

ETH_P_ARP = 0x0806
ARP_REQUEST = 1
ICMPV6_ROUTER_ADVERTISEMENT = 134
DHCP_OFFER = 2
NO_ADDRESS = "0.0.0.0"  # noqa: S104 - the sender address of ARP probes, not a bind address


# Parsing


def mac_text(raw: bytes) -> str:
    return ":".join(f"{byte:02x}" for byte in raw)


def parse_arp(frame: bytes) -> tuple[int, str, str, str] | None:
    """Ethernet frame to (operation, sender MAC, sender IP, target IP)."""
    if len(frame) < 42 or frame[12:14] != b"\x08\x06":
        return None
    htype, ptype, hlen, plen, operation = struct.unpack("!HHBBH", frame[14:22])
    if (htype, ptype, hlen, plen) != (1, 0x0800, 6, 4):
        return None
    sender_mac = mac_text(frame[22:28])
    sender_ip = socket.inet_ntoa(frame[28:32])
    target_ip = socket.inet_ntoa(frame[38:42])
    return operation, sender_mac, sender_ip, target_ip


def router_advertisement_mac(message: bytes) -> str | None:
    """Source link-layer address option of an ICMPv6 router advertisement."""
    if len(message) < 16 or message[0] != ICMPV6_ROUTER_ADVERTISEMENT:
        return None
    offset = 16
    while offset + 2 <= len(message):
        kind, length = message[offset], message[offset + 1] * 8
        if length == 0:
            return None
        if kind == 1 and length >= 8:
            return mac_text(message[offset + 2 : offset + 8])
        offset += length
    return None


def dhcp_offer_server(message: bytes, xid: int) -> str | None:
    """Server identifier of a DHCP offer answering our transaction, or None."""
    if len(message) < 240 or message[0] != 2 or struct.unpack("!I", message[4:8])[0] != xid:
        return None
    if message[236:240] != b"\x63\x82\x53\x63":
        return None
    options, offset, kind, server = message, 240, None, None
    while offset < len(options) and options[offset] != 255:
        code = options[offset]
        if code == 0:
            offset += 1
            continue
        if offset + 1 >= len(options):
            break
        length = options[offset + 1]
        value = options[offset + 2 : offset + 2 + length]
        if code == 53 and length == 1:
            kind = value[0]
        elif code == 54 and length == 4:
            server = socket.inet_ntoa(value)
        offset += 2 + length
    return server if kind == DHCP_OFFER else None


def dns_has_answer(message: bytes, query_id: int) -> bool:
    """True if a DNS-format reply (LLMNR, mDNS) to our query carries answers."""
    if len(message) < 12:
        return False
    reply_id, flags, _, answers = struct.unpack("!HHHH", message[:8])
    return answers > 0 and (flags & 0x8000) != 0 and reply_id == query_id


def nbns_has_answer(message: bytes, query_id: int) -> bool:
    if len(message) < 12:
        return False
    reply_id, flags, _, answers = struct.unpack("!HHHH", message[:8])
    return reply_id == query_id and (flags & 0x8000) != 0 and answers > 0


# Detection


class ArpMonitor:
    """Detects sweeps and spoofing from the ARP traffic every host can see."""

    def __init__(self, own_macs: set[str], gateway_ip: str | None) -> None:
        self.own_macs = own_macs
        self.gateway_ip = gateway_ip
        self._bindings: dict[str, tuple[str, float]] = {}
        self._targets: dict[str, dict[str, float]] = {}

    def observe(self, at: float, arp: tuple[int, str, str, str]) -> list[dict]:
        operation, sender_mac, sender_ip, target_ip = arp
        if sender_mac in self.own_macs:
            return []
        events = []
        if sender_ip != NO_ADDRESS:
            spoof = self._check_binding(at, sender_mac, sender_ip)
            if spoof:
                events.append(spoof)
        if operation == ARP_REQUEST and target_ip != sender_ip:
            sweep = self._check_sweep(at, sender_mac, sender_ip, target_ip)
            if sweep:
                events.append(sweep)
        return events

    def _check_binding(self, at: float, mac: str, ip: str) -> dict | None:
        known = self._bindings.get(ip)
        alive = GATEWAY_BINDING_ALIVE_SECONDS if ip == self.gateway_ip else BINDING_ALIVE_SECONDS
        if known and known[0] != mac and at - known[1] < alive:
            return {
                "occurred_at": iso_time(at),
                "kind": "arp_spoof",
                "src_mac": mac,
                "detail": {"claimed_ip": ip, "previous_mac": known[0]},
            }
        if known or len(self._bindings) < MAX_TRACKED:
            self._bindings[ip] = (mac, at)
        return None

    def _check_sweep(self, at: float, mac: str, ip: str, target: str) -> dict | None:
        if mac not in self._targets and len(self._targets) >= MAX_TRACKED:
            return None
        targets = self._targets.setdefault(mac, {})
        targets[target] = at
        for address in [t for t, seen in targets.items() if at - seen > SWEEP_WINDOW_SECONDS]:
            del targets[address]
        if len(targets) < SWEEP_MIN_TARGETS:
            return None
        del self._targets[mac]
        event = {
            "occurred_at": iso_time(at),
            "kind": "host_scan",
            "src_mac": mac,
            "detail": {"targets": len(targets)},
        }
        if ip != NO_ADDRESS:
            event["src_ip"] = ip
        return event


def newcomers(known: list[str], seen: set[str], prefer: str | None) -> tuple[list[str], list[str]]:
    """Separates legitimate from rogue infrastructure (DHCP servers, routers).

    The first set seen is learned as legitimate; if several appear at once,
    only the preferred one (the default gateway) is. Returns the updated
    known list and the newcomers to report.
    """
    if not known:
        if len(seen) <= 1 or prefer not in seen:
            return sorted(seen), []
        return [prefer], sorted(seen - {prefer})
    return known, sorted(seen - set(known))


# Network I/O


def own_macs() -> set[str]:
    macs = set()
    for interface in os.listdir("/sys/class/net"):
        try:
            with open(f"/sys/class/net/{interface}/address") as handle:
                macs.add(handle.read().strip().lower())
        except OSError:
            continue
    return macs - {"00:00:00:00:00:00"}


def interface_mac(interface: str) -> str:
    with open(f"/sys/class/net/{interface}/address") as handle:
        return handle.read().strip().lower()


def default_gateway(route_table: str = "/proc/net/route") -> str | None:
    with open(route_table) as handle:
        for line in handle.read().splitlines()[1:]:
            fields = line.split()
            if int(fields[1], 16) == 0 and int(fields[2], 16) != 0:
                return str(ipaddress.IPv4Address(bytes.fromhex(fields[2])[::-1]))
    return None


class Listeners:
    """Non-blocking raw sockets for ARP and IPv6 router advertisements."""

    def __init__(self) -> None:
        self.arp = socket.socket(socket.AF_PACKET, socket.SOCK_RAW, socket.htons(ETH_P_ARP))
        self.arp.setblocking(False)
        self.icmpv6 = socket.socket(socket.AF_INET6, socket.SOCK_RAW, socket.IPPROTO_ICMPV6)
        self.icmpv6.setblocking(False)

    def arp_frames(self, limit: int = 2000) -> list[bytes]:
        return _drain(self.arp, limit)

    def router_advertisements(self, limit: int = 100) -> list[tuple[str, str]]:
        """(router link-local address, router MAC) for each advertisement."""
        found = []
        for _ in range(limit):
            try:
                message, address = self.icmpv6.recvfrom(2048)
            except BlockingIOError:
                break
            mac = router_advertisement_mac(message)
            if mac:
                found.append((address[0].split("%")[0], mac))
        return found


def _drain(sock: socket.socket, limit: int) -> list[bytes]:
    frames = []
    for _ in range(limit):
        try:
            frames.append(sock.recv(2048))
        except BlockingIOError:
            break
    return frames


def _dns_query(name: str, query_id: int) -> bytes:
    labels = b"".join(bytes([len(part)]) + part.encode() for part in name.split("."))
    return (
        struct.pack("!HHHHHH", query_id, 0, 1, 0, 0, 0)
        + labels
        + b"\x00"
        + struct.pack("!HH", 1, 1)
    )


def _nbns_query(name: str, query_id: int) -> bytes:
    padded = name.upper()[:15].ljust(15) + "\x00"
    encoded = "".join(chr(0x41 + (ord(c) >> 4)) + chr(0x41 + (ord(c) & 0x0F)) for c in padded)
    header = struct.pack("!HHHHHH", query_id, 0x0110, 1, 0, 0, 0)
    return header + b"\x20" + encoded.encode() + b"\x00" + struct.pack("!HH", 0x20, 1)


def decoy_name() -> str:
    """A plausible host name that exists nowhere on the network."""
    stem = random.choice(["fileserver", "backup", "printer", "nas", "office-pc"])  # noqa: S311 - not a secret
    return f"{stem}-{secrets.randbelow(9000) + 1000}"


def probe_name_poisoning() -> list[tuple[str, str]]:
    """Asks for a non-existent name over LLMNR, mDNS and NBT-NS.

    Returns (responder IP, protocol) for every answer.
    """
    name = decoy_name()
    query_id = secrets.randbelow(0xFFFF) + 1
    probes = [
        ("llmnr", ("224.0.0.252", 5355), _dns_query(name, query_id), dns_has_answer),
        ("mdns", ("224.0.0.251", 5353), _dns_query(name + ".local", query_id), dns_has_answer),
        ("nbns", ("255.255.255.255", 137), _nbns_query(name, query_id), nbns_has_answer),
    ]
    sockets = {}
    for protocol, destination, query, check in probes:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 1)
        sock.sendto(query, destination)
        sockets[sock] = (protocol, check)
    return _collect(sockets, query_id)


def _collect(sockets: dict, query_id: int) -> list[tuple[str, str]]:
    found = set()
    deadline = time.monotonic() + PROBE_WAIT_SECONDS
    try:
        while (remaining := deadline - time.monotonic()) > 0:
            ready, _, _ = select.select(list(sockets), [], [], remaining)
            for sock in ready:
                message, address = sock.recvfrom(2048)
                protocol, check = sockets[sock]
                if check(message, query_id):
                    found.add((address[0], protocol))
    finally:
        for sock in sockets:
            sock.close()
    return sorted(found)


def probe_dhcp_servers(mac: str) -> set[str]:
    """Broadcasts a DHCP discover and returns the identifiers of all servers
    that offer an address. Offers expire unused; nothing is leased."""
    xid = secrets.randbits(32)
    chaddr = bytes.fromhex(mac.replace(":", "")).ljust(16, b"\x00")
    discover = (
        struct.pack("!BBBBIHH", 1, 1, 6, 0, xid, 0, 0x8000)
        + b"\x00" * 16
        + chaddr
        + b"\x00" * 192
        + b"\x63\x82\x53\x63"
        + bytes([53, 1, 1, 255])
    )
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    servers = set()
    try:
        sock.bind(("0.0.0.0", 68))  # noqa: S104 - DHCP replies are broadcast to port 68
        sock.sendto(discover, ("255.255.255.255", 67))
        deadline = time.monotonic() + PROBE_WAIT_SECONDS
        while (remaining := deadline - time.monotonic()) > 0:
            ready, _, _ = select.select([sock], [], [], remaining)
            if not ready:
                break
            server = dhcp_offer_server(sock.recv(2048), xid)
            if server:
                servers.add(server)
    finally:
        sock.close()
    return servers
