"""What the hosts on the network say about themselves (ADR 0010).

Devices announce their names and services in DHCP requests, mDNS, SSDP,
NetBIOS and LLDP. The agent only listens, apart from one mDNS service
enumeration and one SSDP search per inventory cycle (lan.ask_for_services).

Every byte here is attacker-controlled. Parsers check every length against
the frame, read a bounded number of records, and drop a message at the first
inconsistency. Strings are cleaned and bounded before they are stored, and
the store is bounded in hosts and in values per field.
"""

import re
import socket
import struct

from .classify import classify
from .lan import (
    DHCP_SERVER_PORT,
    DNS_A,
    DNS_PTR,
    MDNS_PORT,
    NETBIOS_DATAGRAM_PORT,
    NETBIOS_NAME_PORT,
    NO_ADDRESS,
    SSDP_PORT,
    dhcp_options,
    mac_text,
)
from .sources import clean_text

MAX_HOSTS = 1024
MAX_VALUES = 16
# The bound of network_hosts.hostname and model, and of every stored string.
MAX_TEXT = 64
MAX_DNS_RECORDS = 64
MAX_DNS_JUMPS = 16
MAX_SSDP_LINES = 32
MAX_LLDP_TLVS = 32

ETHERTYPE_IPV4 = b"\x08\x00"
ETHERTYPE_LLDP = b"\x88\xcc"
DNS_TXT, DNS_AAAA, DNS_SRV = 16, 28, 33
DNS_SD_ENUMERATION = "_services._dns-sd._udp.local"
# TXT keys that name the model: Apple device info, AirPlay and companion
# link, Google Cast, IPP printers.
MODEL_KEYS = {"model", "am", "rpmd", "md", "ty", "usb_mdl"}
SERVICE_LABEL = re.compile(r"^[a-z0-9-]{1,32}$")

NETBIOS_REGISTRATION_OPCODES = {5, 8, 9}
NETBIOS_HOST_SUFFIXES = {0x00, 0x20}
NETBIOS_GROUP = 0x8000

# IEEE 802.1AB system capability bits, lowest first.
LLDP_CAPABILITIES = (
    "other",
    "repeater",
    "bridge",
    "wlan-ap",
    "router",
    "telephone",
    "docsis",
    "station",
)

# The order in which sources name a host: what it calls itself first.
NAME_FIELDS = ("name.dhcp", "name.mdns", "name.netbios", "name.lldp", "name.instance")
MODEL_FIELDS = ("model.mdns", "model.lldp")

Fact = tuple[str, str]


# Parsing


def parse_frame(frame: bytes) -> tuple[str, str | None, list[Fact]] | None:
    """Sender MAC, sender IPv4 address and what a discovery frame says."""
    if len(frame) < 14:
        return None
    sender = mac_text(frame[6:12])
    if frame[12:14] == ETHERTYPE_LLDP:
        chassis, address, facts = parse_lldp(frame[14:])
        return chassis or sender, address, facts
    if frame[12:14] != ETHERTYPE_IPV4 or len(frame) < 34 or frame[14] >> 4 != 4:
        return None
    header_length = (frame[14] & 0x0F) * 4
    udp = 14 + header_length
    if header_length < 20 or frame[23] != socket.IPPROTO_UDP or len(frame) < udp + 8:
        return None
    source_port, destination_port, length = struct.unpack("!HHH", frame[udp : udp + 6])
    payload = frame[udp + 8 : udp + max(length, 8)]
    ports = {source_port, destination_port}
    if destination_port == DHCP_SERVER_PORT:
        facts = parse_dhcp_request(payload)
    elif MDNS_PORT in ports:
        facts = parse_mdns(payload, frame[26:30])
    elif SSDP_PORT in ports:
        facts = parse_ssdp(payload)
    elif destination_port == NETBIOS_NAME_PORT:
        facts = parse_netbios_registration(payload)
    elif destination_port == NETBIOS_DATAGRAM_PORT:
        facts = parse_netbios_datagram(payload)
    else:
        return None
    address = socket.inet_ntoa(frame[26:30])
    return sender, None if address == NO_ADDRESS else address, facts


def parse_dhcp_request(message: bytes) -> list[Fact]:
    """Host name, vendor class and parameter request list of a DHCP client."""
    if len(message) < 240 or message[0] != 1:
        return []
    options = dhcp_options(message)
    facts = []
    if 12 in options:
        facts.append(("name.dhcp", options[12].decode("utf-8", "replace")))
    if 60 in options:
        facts.append(("dhcp_class", options[60].decode("utf-8", "replace")))
    if 55 in options:
        facts.append(("dhcp_params", ",".join(str(code) for code in options[55])))
    return facts


def parse_mdns(message: bytes, source: bytes) -> list[Fact]:
    """Names, services and models from an mDNS response sent by source.

    Queries are ignored: their known-answer lists describe other hosts. So
    are responses with an address record for another host, such as those of
    a sleep proxy answering for a sleeping computer.
    """
    if len(message) < 12:
        return []
    flags, questions, answers, authority, additional = struct.unpack("!HHHHH", message[2:12])
    if not flags & 0x8000:
        return []
    try:
        records = dns_records(message, questions, answers + authority + additional)
        facts = [fact for record in records for fact in _mdns_facts(message, *record)]
    except ValueError:
        return []
    if any(kind == DNS_A and message[start:end] != source for _, kind, start, end in records):
        return []
    return facts


def dns_records(message: bytes, questions: int, count: int) -> list[tuple[str, int, int, int]]:
    """(owner, type, data start, data end) of each resource record."""
    offset = 12
    for _ in range(min(questions, MAX_DNS_RECORDS)):
        offset = _dns_name(message, offset)[1] + 4
    records = []
    for _ in range(min(count, MAX_DNS_RECORDS)):
        owner, offset = _dns_name(message, offset)
        if offset + 10 > len(message):
            raise ValueError("record runs past the message")
        record_type, length = struct.unpack("!H6xH", message[offset : offset + 10])
        start, offset = offset + 10, offset + 10 + length
        if offset > len(message):
            raise ValueError("record data runs past the message")
        records.append((owner, record_type, start, offset))
    return records


def _mdns_facts(message: bytes, owner: str, record_type: int, start: int, end: int) -> list[Fact]:
    services = _service(owner)
    if record_type == DNS_PTR:
        return _mdns_pointer(owner, _dns_name(message, start)[0], services)
    if record_type == DNS_SRV:
        # Priority, weight and port precede the target host.
        if end - start <= 6:
            raise ValueError("short SRV record")
        return [*services, ("name.mdns", _host(_dns_name(message, start + 6)[0]))]
    if record_type == DNS_TXT:
        return services + [("model.mdns", model) for model in _txt_models(message[start:end])]
    if record_type in (DNS_A, DNS_AAAA) and owner.endswith(".local"):
        return [("name.mdns", _host(owner))]
    return services


def _mdns_pointer(owner: str, target: str, services: list[Fact]) -> list[Fact]:
    """A service type in the enumeration, a host name for an address, or an
    instance of a service."""
    if owner == DNS_SD_ENUMERATION:
        return _service(target)
    if owner.endswith(".arpa"):
        return [("name.mdns", _host(target))]
    return [*services, ("name.instance", target.partition(".")[0])] if services else []


def _dns_name(message: bytes, offset: int) -> tuple[str, int]:
    """A possibly compressed DNS name and the offset after it."""
    labels: list[str] = []
    end = None
    for _ in range(MAX_DNS_JUMPS):
        while (length := _byte(message, offset)) < 0xC0:
            if length >= 0x40 or offset + 1 + length > len(message):
                raise ValueError("bad label")
            if length == 0:
                return ".".join(labels), end if end is not None else offset + 1
            labels.append(message[offset + 1 : offset + 1 + length].decode("utf-8", "replace"))
            offset += 1 + length
        end = offset + 2 if end is None else end
        offset = ((length & 0x3F) << 8) | _byte(message, offset + 1)
    raise ValueError("too many compression pointers")


def _byte(message: bytes, offset: int) -> int:
    if offset >= len(message):
        raise ValueError("name runs past the message")
    return message[offset]


def _service(name: str) -> list[Fact]:
    """The service label of a DNS-SD name such as "Kitchen._airplay._tcp.local"."""
    labels = name.lower().split(".")
    if len(labels) < 3 or labels[-1] != "local" or labels[-2] not in ("_tcp", "_udp"):
        return []
    label = labels[-3].removeprefix("_")
    return [("service", label)] if SERVICE_LABEL.match(label) else []


def _host(name: str) -> str:
    return name.removesuffix(".local")


def _txt_models(data: bytes) -> list[str]:
    models = []
    offset = 0
    while offset < len(data):
        length = data[offset]
        key, _, value = data[offset + 1 : offset + 1 + length].partition(b"=")
        if key.decode("ascii", "replace").lower() in MODEL_KEYS and value:
            models.append(value.decode("utf-8", "replace"))
        offset += 1 + length
    return models


def parse_ssdp(message: bytes) -> list[Fact]:
    """Device and service types, the server string and the description URL
    of SSDP announcements and search responses. Searches by others say what
    they look for, not what they are, and are ignored."""
    lines = message.decode("utf-8", "replace").split("\r\n")[:MAX_SSDP_LINES]
    if not lines[0].startswith(("NOTIFY * ", "HTTP/1.1 200")):
        return []
    facts = []
    for line in lines[1:]:
        name, _, value = line.partition(":")
        name, value = name.strip().upper(), value.strip()
        if (name in ("NT", "ST") and value.lower().startswith("urn:")) or name == "SERVER":
            facts.append(("upnp", value))
        elif name == "LOCATION":
            facts.append(("location", value))
    return facts


def _netbios_name(encoded: bytes) -> tuple[str, int] | None:
    """First-level decoding (RFC 1001, 14.1): name and suffix byte."""
    if len(encoded) != 32 or any(not 0x41 <= c <= 0x50 for c in encoded):
        return None
    raw = bytes(((encoded[i] - 0x41) << 4) | (encoded[i + 1] - 0x41) for i in range(0, 32, 2))
    return raw[:15].decode("ascii", "replace").strip(), raw[15]


def parse_netbios_registration(message: bytes) -> list[Fact]:
    """The name a host registers or refreshes by broadcast (RFC 1002, 4.2.2).

    Queries name the host being looked for and are ignored, as are group
    names such as the workgroup.
    """
    if len(message) < 68 or message[2] & 0x80:
        return []
    opcode = (message[2] >> 3) & 0x0F
    # Question: length 32, encoded name, no scope. The additional record
    # points back to it and carries the NB flags.
    if opcode not in NETBIOS_REGISTRATION_OPCODES or message[12] != 0x20 or message[45] != 0:
        return []
    if message[50:52] != b"\xc0\x0c" or struct.unpack("!H", message[62:64])[0] & NETBIOS_GROUP:
        return []
    decoded = _netbios_name(message[13:45])
    if decoded is None or decoded[1] not in NETBIOS_HOST_SUFFIXES:
        return []
    return [("name.netbios", decoded[0])]


def parse_netbios_datagram(message: bytes) -> list[Fact]:
    """The source name of a NetBIOS datagram, such as a browser announcement."""
    if len(message) < 48 or message[0] not in (0x10, 0x11, 0x12) or message[14] != 0x20:
        return []
    decoded = _netbios_name(message[15:47])
    if decoded is None or decoded[1] not in NETBIOS_HOST_SUFFIXES:
        return []
    return [("name.netbios", decoded[0])]


def parse_lldp(data: bytes) -> tuple[str | None, str | None, list[Fact]]:
    """Chassis MAC, management IPv4 address and what an LLDP frame says."""
    chassis = address = None
    facts = []
    offset = 0
    for _ in range(MAX_LLDP_TLVS):
        if offset + 2 > len(data):
            break
        header = struct.unpack("!H", data[offset : offset + 2])[0]
        kind, length = header >> 9, header & 0x1FF
        value = data[offset + 2 : offset + 2 + length]
        offset += 2 + length
        if kind == 0 or len(value) < length:
            break
        if kind == 1 and length == 7 and value[0] == 4:
            chassis = mac_text(value[1:7])
        elif kind == 5:
            facts.append(("name.lldp", value.decode("utf-8", "replace")))
        elif kind == 6:
            facts.append(("model.lldp", value.decode("utf-8", "replace")))
        elif kind == 7 and length == 4:
            enabled = struct.unpack("!H", value[2:4])[0]
            names = [name for bit, name in enumerate(LLDP_CAPABILITIES) if enabled & (1 << bit)]
            facts.append(("lldp", ",".join(names)))
        elif kind == 8 and length >= 6 and value[0] == 5 and value[1] == 1:
            address = socket.inet_ntoa(value[2:6])
    return chassis, address, facts


# What is known


class Identities:
    """What each host has said about itself, newest last."""

    def __init__(self) -> None:
        self._hosts: dict[str, dict[str, list[str]]] = {}

    def observe(self, mac: str, facts: list[Fact]) -> None:
        cleaned = [(field, text) for field, value in facts if (text := _bounded(value))]
        if not cleaned:
            return
        known = self._hosts.pop(mac, {})
        for field, text in cleaned:
            values = known.setdefault(field, [])
            if text in values:
                values.remove(text)
            values.append(text)
            del values[:-MAX_VALUES]
        self._hosts[mac] = known
        if len(self._hosts) > MAX_HOSTS:
            del self._hosts[next(iter(self._hosts))]

    def describe(self, host: dict) -> dict:
        """The host as the inventory found it, with its identification."""
        known = self._hosts.get(host["mac"], {})
        evidence = known | ({"vendor": [host["vendor"]]} if "vendor" in host else {})
        kind, os = classify(evidence)
        described = host | {"kind": kind}
        if os:
            described["os"] = os
        if hostname := _latest(known, NAME_FIELDS):
            described["hostname"] = hostname
        if model := _latest(known, MODEL_FIELDS):
            described["model"] = model
        if services := sorted(known.get("service", [])):
            described["services"] = services
        return described


def _bounded(value: str) -> str:
    return clean_text(value)[:MAX_TEXT].strip()


def _latest(known: dict[str, list[str]], fields: tuple[str, ...]) -> str | None:
    return next((known[field][-1] for field in fields if known.get(field)), None)
