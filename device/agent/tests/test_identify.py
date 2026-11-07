import random
import re
import socket
import struct

from dekoi_agent.identify import (
    MAX_HOSTS,
    Identities,
    parse_frame,
    parse_mdns,
    parse_netbios_registration,
)
from dekoi_agent.lan import MAX_FRAME, NO_ADDRESS, discovery_filter

PHONE_MAC = "62:1f:9a:00:00:01"
TV_MAC = "a8:23:fe:00:00:02"
SWITCH_MAC = "74:ac:b9:00:00:03"
OWN_MAC = "dc:a6:32:00:00:01"


def mac_bytes(mac: str) -> bytes:
    return bytes.fromhex(mac.replace(":", ""))


def udp_frame(
    source_mac: str,
    source: tuple[str, int],
    destination: tuple[str, int],
    payload: bytes,
    fragment: int = 0,
) -> bytes:
    (source_ip, source_port), (destination_ip, destination_port) = source, destination
    udp = struct.pack("!HHHH", source_port, destination_port, 8 + len(payload), 0) + payload
    ip = struct.pack(
        "!BBHHHBBH4s4s",
        0x45,
        0,
        20 + len(udp),
        1,
        fragment,
        255,
        17,
        0,
        socket.inet_aton(source_ip),
        socket.inet_aton(destination_ip),
    )
    return b"\x01\x00\x5e\x00\x00\xfb" + mac_bytes(source_mac) + b"\x08\x00" + ip + udp


def dns_name(name: str) -> bytes:
    return b"".join(bytes([len(label)]) + label.encode() for label in name.split(".")) + b"\x00"


def dns_record(owner: bytes, record_type: int, data: bytes) -> bytes:
    return owner + struct.pack("!HHIH", record_type, 0x8001, 120, len(data)) + data


def airplay_response(address: str = "192.168.1.40") -> bytes:
    """An Apple TV announcing itself, with a compressed instance name."""
    header = struct.pack("!HHHHHH", 0, 0x8400, 0, 4, 0, 0)
    pointer = dns_record(dns_name("_airplay._tcp.local"), 12, b"\x0bLiving Room\xc0\x0c")
    instance = struct.pack("!H", 0xC000 | (len(header) + len(pointer) - 14))
    service = dns_record(instance, 33, struct.pack("!HHH", 0, 0, 7000) + dns_name("Apple-TV.local"))
    text = dns_record(instance, 16, b"\x10model=AppleTV6,2\x0dsrcvers=670.6")
    host = dns_record(dns_name("Apple-TV.local"), 1, socket.inet_aton(address))
    return header + pointer + service + text + host


def dhcp_request(mac: str, options: bytes) -> bytes:
    bootp = struct.pack("!BBBBIHH", 1, 1, 6, 0, 0x1234, 0, 0) + b"\x00" * 16
    bootp += mac_bytes(mac).ljust(16, b"\x00") + b"\x00" * 192
    return bootp + b"\x63\x82\x53\x63" + bytes([53, 1, 3]) + options + b"\xff"


def option(code: int, value: bytes) -> bytes:
    return bytes([code, len(value)]) + value


def netbios_encode(name: str, suffix: int) -> bytes:
    raw = name.upper().ljust(15).encode() + bytes([suffix])
    return bytes(c for byte in raw for c in (0x41 + (byte >> 4), 0x41 + (byte & 0x0F)))


def netbios_registration(name: str, suffix: int = 0x00, group: bool = False) -> bytes:
    header = struct.pack("!HHHHHH", 0x1234, 0x2910, 1, 0, 0, 1)
    question = b"\x20" + netbios_encode(name, suffix) + b"\x00" + struct.pack("!HH", 0x20, 1)
    flags = 0x8000 if group else 0
    additional = b"\xc0\x0c" + struct.pack(
        "!HHIHH4s", 0x20, 1, 300000, 6, flags, b"\xc0\xa8\x01\x15"
    )
    return header + question + additional


def lldp_frame(source_mac: str, tlvs: list[tuple[int, bytes]]) -> bytes:
    body = b"".join(struct.pack("!H", (kind << 9) | len(value)) + value for kind, value in tlvs)
    return b"\x01\x80\xc2\x00\x00\x0e" + mac_bytes(source_mac) + b"\x88\xcc" + body + b"\x00\x00"


def run_filter(program: bytes, frame: bytes) -> int:
    """A classic BPF interpreter for the opcodes discovery_filter uses."""
    instructions = [struct.unpack("HBBI", program[i : i + 8]) for i in range(0, len(program), 8)]
    accumulator = index = pc = 0

    def load(offset: int, size: int) -> int | None:
        if offset + size > len(frame):
            return None
        return int.from_bytes(frame[offset : offset + size], "big")

    while True:
        code, if_true, if_false, k = instructions[pc]
        pc += 1
        loaded = {0x28: (k, 2), 0x30: (k, 1), 0xB1: (k, 1), 0x48: (index + k, 2)}.get(code)
        if loaded:
            value = load(*loaded)
            if value is None:
                return 0
            if code == 0xB1:
                index = (value & 0x0F) * 4
            else:
                accumulator = value
        elif code == 0x15:
            pc += if_true if accumulator == k else if_false
        elif code == 0x45:
            pc += if_true if accumulator & k else if_false
        elif code == 0x06:
            return k
        else:
            raise AssertionError(f"unexpected opcode {code:#x}")


def test_mdns_names_services_and_model() -> None:
    frame = udp_frame(TV_MAC, ("192.168.1.40", 5353), ("224.0.0.251", 5353), airplay_response())
    mac, address, facts = parse_frame(frame)
    assert (mac, address) == (TV_MAC, "192.168.1.40")
    assert set(facts) == {
        ("service", "airplay"),
        ("name.instance", "Living Room"),
        ("name.mdns", "Apple-TV"),
        ("model.mdns", "AppleTV6,2"),
    }


def test_mdns_service_enumeration_answer() -> None:
    header = struct.pack("!HHHHHH", 0, 0x8400, 0, 2, 0, 0)
    owner = dns_name("_services._dns-sd._udp.local")
    message = header
    for service in ("_ipp._tcp.local", "_Printer._tcp.local"):
        message += dns_record(owner, 12, dns_name(service))
    assert parse_mdns(message, socket.inet_aton("192.168.1.30")) == [
        ("service", "ipp"),
        ("service", "printer"),
    ]


def test_mdns_queries_and_answers_for_other_hosts_are_ignored() -> None:
    response = airplay_response()
    query = response[:2] + b"\x00\x00" + response[4:]
    assert parse_mdns(query, socket.inet_aton("192.168.1.40")) == []
    sleep_proxy = socket.inet_aton("192.168.1.2")
    assert parse_mdns(response, sleep_proxy) == []


def test_malformed_mdns_is_dropped_whole() -> None:
    response = airplay_response()
    source = socket.inet_aton("192.168.1.40")
    assert parse_mdns(response[:-3], source) == [], "truncated"
    header = struct.pack("!HHHHHH", 0, 0x8400, 0, 1, 0, 0)
    loop = header + b"\xc0\x0c" + struct.pack("!HHIH", 1, 1, 120, 4) + source
    assert parse_mdns(loop, source) == [], "compression loop"
    label = header + b"\x50" + b"x" * 80 + b"\x00" + struct.pack("!HHIH", 1, 1, 120, 4) + source
    assert parse_mdns(label, source) == [], "reserved label type"


def test_dhcp_request_name_class_and_parameters() -> None:
    options = option(12, b"Galaxy-S23") + option(60, b"android-dhcp-14")
    options += option(55, bytes([1, 3, 6, 15, 26, 28, 51, 58, 59, 43, 114, 108]))
    frame = udp_frame(
        PHONE_MAC, (NO_ADDRESS, 68), ("255.255.255.255", 67), dhcp_request(PHONE_MAC, options)
    )
    mac, address, facts = parse_frame(frame)
    assert (mac, address) == (PHONE_MAC, None)
    assert facts == [
        ("name.dhcp", "Galaxy-S23"),
        ("dhcp_class", "android-dhcp-14"),
        ("dhcp_params", "1,3,6,15,26,28,51,58,59,43,114,108"),
    ]


def test_ssdp_announcements_and_search_answers() -> None:
    notify = (
        b"NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: urn:schemas-upnp-org:device:"
        b"MediaRenderer:1\r\nNTS: ssdp:alive\r\nSERVER: Linux/4.4 UPnP/1.0 Sonos/77.4 (ZPS9)\r\n"
        b"LOCATION: http://192.168.1.50:1400/xml/device_description.xml\r\n\r\n"
    )
    frame = udp_frame(TV_MAC, ("192.168.1.50", 1900), ("239.255.255.250", 1900), notify)
    assert parse_frame(frame)[2] == [
        ("upnp", "urn:schemas-upnp-org:device:MediaRenderer:1"),
        ("upnp", "Linux/4.4 UPnP/1.0 Sonos/77.4 (ZPS9)"),
        ("location", "http://192.168.1.50:1400/xml/device_description.xml"),
    ]
    answer = (
        b"HTTP/1.1 200 OK\r\nST: urn:dial-multiscreen-org:service:dial:1\r\nUSN: uuid:1\r\n\r\n"
    )
    frame = udp_frame(TV_MAC, ("192.168.1.50", 1900), ("192.168.1.20", 50123), answer)
    assert parse_frame(frame)[2] == [("upnp", "urn:dial-multiscreen-org:service:dial:1")]
    search = (
        b'M-SEARCH * HTTP/1.1\r\nMAN: "ssdp:discover"\r\n'
        b"ST: urn:schemas-upnp-org:device:Printer:1\r\n\r\n"
    )
    frame = udp_frame(PHONE_MAC, ("192.168.1.60", 50124), ("239.255.255.250", 1900), search)
    assert parse_frame(frame)[2] == []


def test_netbios_registrations_name_the_sender_only() -> None:
    assert parse_netbios_registration(netbios_registration("DESKTOP-4F2K9QX")) == [
        ("name.netbios", "DESKTOP-4F2K9QX")
    ]
    assert parse_netbios_registration(netbios_registration("WORKGROUP", group=True)) == []
    assert parse_netbios_registration(netbios_registration("DESKTOP-4F2K9QX", suffix=0x1E)) == []
    query = bytearray(netbios_registration("FILESERVER"))
    query[2:4] = b"\x01\x10"
    assert parse_netbios_registration(bytes(query)) == []


def test_netbios_datagram_source_name() -> None:
    header = struct.pack("!BBH4sHHH", 0x11, 0x02, 1, b"\xc0\xa8\x01\x15", 138, 0, 0)
    names = b"\x20" + netbios_encode("KARI-LAPTOP", 0x00) + b"\x00"
    names += b"\x20" + netbios_encode("WORKGROUP", 0x1D) + b"\x00"
    frame = udp_frame(PHONE_MAC, ("192.168.1.21", 138), ("192.168.1.255", 138), header + names)
    assert parse_frame(frame)[2] == [("name.netbios", "KARI-LAPTOP")]


def test_lldp_names_capabilities_and_chassis() -> None:
    frame = lldp_frame(
        "74:ac:b9:00:00:13",
        [
            (1, b"\x04" + mac_bytes(SWITCH_MAC)),
            (2, b"\x07" + b"port 3"),
            (3, b"\x00\x78"),
            (5, b"USW-Lite-8-PoE"),
            (6, b"USW-Lite-8-PoE, 7.0.50.15613"),
            (7, struct.pack("!HH", 0x0014, 0x0004)),
            (8, b"\x05\x01" + socket.inet_aton("192.168.1.2") + b"\x02\x00\x00\x00\x01\x00"),
        ],
    )
    mac, address, facts = parse_frame(frame)
    assert (mac, address) == (SWITCH_MAC, "192.168.1.2")
    assert facts == [
        ("name.lldp", "USW-Lite-8-PoE"),
        ("model.lldp", "USW-Lite-8-PoE, 7.0.50.15613"),
        ("lldp", "bridge"),
    ]


def test_hostile_frames_never_raise() -> None:
    rng = random.Random(4242)  # noqa: S311 - reproducible fuzzing, not a secret
    samples = [
        udp_frame(TV_MAC, ("192.168.1.40", 5353), ("224.0.0.251", 5353), airplay_response()),
        udp_frame(
            PHONE_MAC, (NO_ADDRESS, 68), ("255.255.255.255", 67), dhcp_request(PHONE_MAC, b"")
        ),
        udp_frame(
            PHONE_MAC, ("192.168.1.21", 137), ("192.168.1.255", 137), netbios_registration("A")
        ),
        lldp_frame(SWITCH_MAC, [(5, b"switch"), (7, b"\x00\x04\x00\x04")]),
    ]
    for _ in range(3000):
        frame = bytearray(rng.choice(samples))
        for _ in range(rng.randint(1, 8)):
            frame[rng.randrange(14, len(frame))] = rng.randrange(256)
        frame = frame[: rng.randint(0, len(frame))]
        found = parse_frame(bytes(frame))
        assert found is None or isinstance(found[2], list)
    for _ in range(1000):
        parse_frame(bytes(rng.randrange(256) for _ in range(rng.randint(0, 600))))


def test_described_hosts_meet_the_backend_constraints() -> None:
    identities = Identities()
    hostile = [("name.dhcp", "\x1b[31m" + "x" * 300), ("model.mdns", "Laser\u202ejet\u0000")]
    hostile += [("service", f"s{n}") for n in range(40)]
    identities.observe(PHONE_MAC, hostile)
    host = identities.describe({"mac": PHONE_MAC, "ip": "192.168.1.60"})
    assert 1 <= len(host["hostname"]) <= 64
    assert 1 <= len(host["model"]) <= 64
    assert not re.search(r"[\x00-\x1f\x7f-\x9f\u202e]", host["hostname"] + host["model"])
    assert len(host["services"]) == 16
    assert all(re.fullmatch(r"[a-z0-9-]{1,32}", label) for label in host["services"])


def test_identities_prefer_the_name_a_host_gives_itself() -> None:
    identities = Identities()
    identities.observe(PHONE_MAC, [("name.instance", "Kari's iPhone"), ("name.netbios", "KARI")])
    identities.observe(PHONE_MAC, [("name.dhcp", "Karis-iPhone"), ("dhcp_class", "")])
    host = {"mac": PHONE_MAC, "ip": "192.168.1.60"}
    assert identities.describe(host) == host | {
        "kind": "phone",
        "os": "ios",
        "hostname": "Karis-iPhone",
    }
    assert identities.describe({"mac": TV_MAC, "ip": "192.168.1.40", "vendor": "Sonos, Inc."}) == {
        "mac": TV_MAC,
        "ip": "192.168.1.40",
        "vendor": "Sonos, Inc.",
        "kind": "speaker",
    }


def test_identities_forget_the_least_recently_heard_host() -> None:
    identities = Identities()
    for n in range(MAX_HOSTS + 1):
        identities.observe(f"02:00:00:00:{n >> 8:02x}:{n & 0xFF:02x}", [("name.dhcp", f"h{n}")])
    first = identities.describe({"mac": "02:00:00:00:00:00", "ip": "192.168.1.2"})
    last = identities.describe({"mac": "02:00:00:00:04:00", "ip": "192.168.1.3"})
    assert "hostname" not in first
    assert last["hostname"] == f"h{MAX_HOSTS}"


def test_kernel_filter_passes_discovery_traffic_only() -> None:
    program = discovery_filter()
    mdns = udp_frame(TV_MAC, ("192.168.1.40", 5353), ("224.0.0.251", 5353), b"x")
    ssdp_answer = udp_frame(TV_MAC, ("192.168.1.40", 1900), ("192.168.1.20", 50123), b"x")
    dhcp = udp_frame(PHONE_MAC, (NO_ADDRESS, 68), ("255.255.255.255", 67), b"x")
    lldp = lldp_frame(SWITCH_MAC, [(5, b"switch")])
    with_options = bytearray(udp_frame(TV_MAC, ("192.168.1.40", 5353), ("224.0.0.251", 5353), b"x"))
    with_options[14] = 0x46
    with_options[34:34] = b"\x01\x01\x01\x01"
    for frame in (mdns, ssdp_answer, dhcp, lldp, bytes(with_options)):
        assert run_filter(program, frame) == MAX_FRAME
        assert parse_frame(frame) is not None
    dns = udp_frame(TV_MAC, ("192.168.1.40", 50000), ("192.168.1.1", 53), b"x")
    fragment = udp_frame(TV_MAC, ("192.168.1.40", 5353), ("224.0.0.251", 5353), b"x", fragment=185)
    tcp = bytearray(mdns)
    tcp[23] = 6
    arp = b"\xff" * 6 + mac_bytes(PHONE_MAC) + b"\x08\x06" + b"\x00" * 28
    for frame in (dns, fragment, bytes(tcp), arp, b"\x00" * 10):
        assert run_filter(program, frame) == 0
