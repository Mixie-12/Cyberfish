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

The same vantage point carries the discovery traffic that identifies hosts
(identify.py); its listener is here with the other sockets.

Parsing and detection are pure functions; the socket code is at the bottom.
"""

import ctypes
import ipaddress
import logging
import os
import random
import secrets
import select
import socket
import struct
import time

from .sources import iso_time

log = logging.getLogger(__name__)

SWEEP_WINDOW_SECONDS = 60
SWEEP_MIN_TARGETS = 32
BINDING_ALIVE_SECONDS = 5 * 60
GATEWAY_BINDING_ALIVE_SECONDS = 24 * 60 * 60
MAX_TRACKED = 4096
PROBE_WAIT_SECONDS = 3.0
MAX_FRAME = 2048
MAX_DRAIN = 2000

ETH_P_ARP = 0x0806
ARP_REQUEST = 1
ICMPV6_ROUTER_ADVERTISEMENT = 134
DHCP_OFFER = 2
DHCP_MAGIC = b"\x63\x82\x53\x63"
NO_ADDRESS = "0.0.0.0"  # noqa: S104 - ARP probe sender; bound only to send mDNS from 5353

DHCP_SERVER_PORT = 67
NETBIOS_NAME_PORT = 137
NETBIOS_DATAGRAM_PORT = 138
SSDP_PORT = 1900
MDNS_PORT = 5353
MDNS_GROUP = ("224.0.0.251", MDNS_PORT)
SSDP_GROUP = ("239.255.255.250", SSDP_PORT)
# Answers to our searches come from these ports; announcements and requests
# go to the others.
DISCOVERY_SOURCE_PORTS = (SSDP_PORT, MDNS_PORT)
DISCOVERY_DESTINATION_PORTS = (
    DHCP_SERVER_PORT,
    NETBIOS_NAME_PORT,
    NETBIOS_DATAGRAM_PORT,
    SSDP_PORT,
    MDNS_PORT,
)
# Link-layer groups the network card must pass up: mDNS, SSDP and LLDP to
# the nearest bridge. Broadcasts (DHCP, NetBIOS) always arrive.
DISCOVERY_GROUPS = ("01:00:5e:00:00:fb", "01:00:5e:7f:ff:fa", "01:80:c2:00:00:0e")
DNS_A, DNS_PTR = 1, 12

# Linux constants the socket module does not export (linux/if_packet.h,
# asm-generic/socket.h, linux/if_arp.h).
SOL_PACKET = 263
PACKET_ADD_MEMBERSHIP = 1
PACKET_MR_MULTICAST = 0
SO_ATTACH_FILTER = 26
ARPHRD_ETHER = 1
# Classic BPF opcodes (linux/bpf_common.h): load a half word or byte at a
# fixed offset, load the IPv4 header length into X, load a half word at X
# plus an offset, jump if equal, jump if any bit set, return.
BPF_LDH, BPF_LDB, BPF_LDX_IP_HEADER, BPF_LDH_X = 0x28, 0x30, 0xB1, 0x48
BPF_JEQ, BPF_JSET, BPF_RET = 0x15, 0x45, 0x06
DISCOVERY_RECEIVE_BUFFER = 1 << 20


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


def parse_router_advertisement(message: bytes) -> tuple[str, list[str]] | None:
    """Router MAC (source link-layer address option) and the prefixes an
    ICMPv6 router advertisement announces. None without a router MAC."""
    if len(message) < 16 or message[0] != ICMPV6_ROUTER_ADVERTISEMENT:
        return None
    mac, prefixes = None, []
    offset = 16
    while offset + 2 <= len(message):
        kind, length = message[offset], message[offset + 1] * 8
        option = message[offset : offset + length]
        if length == 0 or len(option) < length:
            return None
        if kind == 1:
            mac = mac_text(option[2:8])
        elif kind == 3 and length == 32 and (prefix := _advertised_prefix(option)):
            prefixes.append(prefix)
        offset += length
    return (mac, prefixes) if mac else None


def _advertised_prefix(option: bytes) -> str | None:
    """A prefix information option (RFC 4861, 4.6.2) that is still valid."""
    length, valid_lifetime = option[2], struct.unpack("!I", option[4:8])[0]
    if valid_lifetime == 0 or not 0 < length <= 128:
        return None
    network = ipaddress.IPv6Network((option[16:32], length), strict=False)
    return None if network.is_link_local or network.is_multicast else str(network)


def dhcp_options(message: bytes) -> dict[int, bytes]:
    """The first value of each option of a BOOTP message with the DHCP cookie."""
    options: dict[int, bytes] = {}
    if message[236:240] != DHCP_MAGIC:
        return options
    offset = 240
    while offset + 1 < len(message) and message[offset] != 255:
        code = message[offset]
        if code == 0:
            offset += 1
            continue
        length = message[offset + 1]
        options.setdefault(code, message[offset + 2 : offset + 2 + length])
        offset += 2 + length
    return options


def dhcp_offer_server(message: bytes, xid: int) -> str | None:
    """Server identifier of a DHCP offer answering our transaction, or None."""
    if len(message) < 240 or message[0] != 2 or struct.unpack("!I", message[4:8])[0] != xid:
        return None
    options = dhcp_options(message)
    server = options.get(54, b"")
    if options.get(53) != bytes([DHCP_OFFER]) or len(server) != 4:
        return None
    return socket.inet_ntoa(server)


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
    """Non-blocking raw sockets for ARP, IPv6 router advertisements and the
    discovery traffic that identifies hosts.

    AF_PACKET sockets see frames before netfilter, so none of this needs a
    firewall opening: the answers to our searches are dropped by nftables
    after the agent has read them.
    """

    def __init__(self) -> None:
        self.arp = socket.socket(socket.AF_PACKET, socket.SOCK_RAW, socket.htons(ETH_P_ARP))
        self.arp.setblocking(False)
        self.icmpv6 = socket.socket(socket.AF_INET6, socket.SOCK_RAW, socket.IPPROTO_ICMPV6)
        self.icmpv6.setblocking(False)
        self.discovery = _discovery_socket()

    def arp_frames(self, limit: int = MAX_DRAIN) -> list[bytes]:
        return _drain(self.arp, limit)

    def discovery_frames(self, limit: int = MAX_DRAIN) -> list[bytes]:
        return _drain(self.discovery, limit)

    def router_advertisements(self, limit: int = 100) -> list[tuple[str, str, list[str]]]:
        """(router link-local address, router MAC, announced prefixes) for
        each advertisement."""
        found = []
        for _ in range(limit):
            try:
                message, address = self.icmpv6.recvfrom(MAX_FRAME)
            except BlockingIOError:
                break
            advertisement = parse_router_advertisement(message)
            if advertisement:
                found.append((address[0].split("%")[0], *advertisement))
        return found


def discovery_filter() -> bytes:
    """Classic BPF program: LLDP, and unfragmented IPv4 UDP from or to a
    discovery port. Every other frame, decoy traffic included, stays in the
    kernel. Accepted frames are cut to MAX_FRAME bytes."""
    accept, drop = "accept", "drop"
    program = [
        (BPF_LDH, None, None, 12),
        (BPF_JEQ, accept, None, 0x88CC),
        (BPF_JEQ, None, drop, 0x0800),
        (BPF_LDB, None, None, 23),
        (BPF_JEQ, None, drop, socket.IPPROTO_UDP),
        (BPF_LDH, None, None, 20),
        (BPF_JSET, drop, None, 0x1FFF),
        (BPF_LDX_IP_HEADER, None, None, 14),
        (BPF_LDH_X, None, None, 14),
        *[(BPF_JEQ, accept, None, port) for port in DISCOVERY_SOURCE_PORTS],
        (BPF_LDH_X, None, None, 16),
        *[(BPF_JEQ, accept, None, port) for port in DISCOVERY_DESTINATION_PORTS],
    ]
    targets = {drop: len(program), accept: len(program) + 1}

    def jump(at: int, target: str | None) -> int:
        return 0 if target is None else targets[target] - at - 1

    instructions = [
        struct.pack("HBBI", code, jump(at, if_true), jump(at, if_false), k)
        for at, (code, if_true, if_false, k) in enumerate(program)
    ]
    instructions += [
        struct.pack("HBBI", BPF_RET, 0, 0, 0),
        struct.pack("HBBI", BPF_RET, 0, 0, MAX_FRAME),
    ]
    return b"".join(instructions)


def _discovery_socket() -> socket.socket:
    sock = socket.socket(socket.AF_PACKET, socket.SOCK_RAW, socket.htons(socket.ETH_P_ALL))
    program = discovery_filter()
    buffer = ctypes.create_string_buffer(program, len(program))
    # struct sock_fprog: instruction count and a pointer to the instructions.
    sock.setsockopt(
        socket.SOL_SOCKET,
        SO_ATTACH_FILTER,
        struct.pack("HP", len(program) // 8, ctypes.addressof(buffer)),
    )
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, DISCOVERY_RECEIVE_BUFFER)
    sock.setblocking(False)
    # Frames that arrived before the filter was attached.
    _drain(sock, MAX_DRAIN)
    for index, name in socket.if_nameindex():
        try:
            _join_discovery_groups(sock, index, name)
        except OSError as error:
            # An interface that went away since it was listed has nothing to join.
            log.info("no discovery groups on %s: %s", name, error)
    return sock


def _join_discovery_groups(sock: socket.socket, index: int, name: str) -> None:
    with open(f"/sys/class/net/{name}/type") as handle:
        if int(handle.read()) != ARPHRD_ETHER:
            return
    for group in DISCOVERY_GROUPS:
        # struct packet_mreq: interface, type, address length, address.
        membership = struct.pack(
            "iHH8s", index, PACKET_MR_MULTICAST, 6, bytes.fromhex(group.replace(":", ""))
        )
        sock.setsockopt(SOL_PACKET, PACKET_ADD_MEMBERSHIP, membership)


def ask_for_services() -> None:
    """One mDNS service enumeration and one SSDP search, the questions any
    storage box asks. The answers reach the discovery listener."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as mdns:
        mdns.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        mdns.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 1)
        # From port 5353, so devices answer by multicast as they do any other
        # mDNS querier, rather than by unicast to a closed port.
        mdns.bind((NO_ADDRESS, MDNS_PORT))
        mdns.sendto(_dns_query("_services._dns-sd._udp.local", 0, DNS_PTR), MDNS_GROUP)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as ssdp:
        ssdp.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 1)
        ssdp.sendto(
            b"M-SEARCH * HTTP/1.1\r\n"
            b"HOST: 239.255.255.250:1900\r\n"
            b'MAN: "ssdp:discover"\r\n'
            b"MX: 2\r\n"
            b"ST: ssdp:all\r\n\r\n",
            SSDP_GROUP,
        )


def _drain(sock: socket.socket, limit: int) -> list[bytes]:
    frames = []
    for _ in range(limit):
        try:
            frames.append(sock.recv(MAX_FRAME))
        except BlockingIOError:
            break
    return frames


def _dns_query(name: str, query_id: int, record_type: int) -> bytes:
    labels = b"".join(bytes([len(part)]) + part.encode() for part in name.split("."))
    return (
        struct.pack("!HHHHHH", query_id, 0, 1, 0, 0, 0)
        + labels
        + b"\x00"
        + struct.pack("!HH", record_type, 1)
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
        ("llmnr", ("224.0.0.252", 5355), _dns_query(name, query_id, DNS_A), dns_has_answer),
        ("mdns", MDNS_GROUP, _dns_query(name + ".local", query_id, DNS_A), dns_has_answer),
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
