import socket
import struct

from dekoi_agent.lan import (
    ArpMonitor,
    _dns_query,
    _nbns_query,
    dhcp_offer_server,
    dns_has_answer,
    nbns_has_answer,
    newcomers,
    parse_arp,
    router_advertisement_mac,
)

T = 1_800_000_000.0
GATEWAY = "192.168.1.1"
GATEWAY_MAC = "aa:bb:cc:00:00:01"
ATTACKER_MAC = "3c:22:fb:00:00:66"
OWN_MAC = "dc:a6:32:00:00:01"


def mac_bytes(mac: str) -> bytes:
    return bytes.fromhex(mac.replace(":", ""))


def arp_frame(operation: int, sender_mac: str, sender_ip: str, target_ip: str) -> bytes:
    ethernet = b"\xff" * 6 + mac_bytes(sender_mac) + b"\x08\x06"
    arp = struct.pack("!HHBBH", 1, 0x0800, 6, 4, operation)
    arp += (
        mac_bytes(sender_mac)
        + socket.inet_aton(sender_ip)
        + b"\x00" * 6
        + socket.inet_aton(target_ip)
    )
    return ethernet + arp


def test_parse_arp() -> None:
    frame = arp_frame(1, ATTACKER_MAC, "192.168.1.66", GATEWAY)
    assert parse_arp(frame) == (1, ATTACKER_MAC, "192.168.1.66", GATEWAY)
    assert parse_arp(frame[:30]) is None
    assert parse_arp(b"\x00" * 12 + b"\x08\x00" + frame[14:]) is None


def test_sweep_of_many_addresses_is_a_host_scan() -> None:
    monitor = ArpMonitor({OWN_MAC}, GATEWAY)
    events = []
    for host in range(2, 40):
        events += monitor.observe(
            T + host * 0.1, (1, ATTACKER_MAC, "192.168.1.66", f"192.168.1.{host}")
        )
    (scan,) = [e for e in events if e["kind"] == "host_scan"]
    assert scan["src_mac"] == ATTACKER_MAC
    assert scan["src_ip"] == "192.168.1.66"
    assert scan["detail"] == {"targets": 32}


def test_ordinary_traffic_and_our_own_scans_are_quiet() -> None:
    monitor = ArpMonitor({OWN_MAC}, GATEWAY)
    events = []
    for second in range(300):
        events += monitor.observe(
            T + second, (1, "aa:bb:cc:00:00:02", "192.168.1.20", f"192.168.1.{second % 5 + 1}")
        )
    for host in range(2, 250):
        events += monitor.observe(
            T + host * 0.01, (1, OWN_MAC, "192.168.1.30", f"192.168.1.{host}")
        )
    assert events == []


def test_claiming_the_routers_address_is_spoofing() -> None:
    monitor = ArpMonitor({OWN_MAC}, GATEWAY)
    assert monitor.observe(T, (2, GATEWAY_MAC, GATEWAY, "192.168.1.20")) == []
    later = T + 6 * 60 * 60
    (spoof,) = monitor.observe(later, (2, ATTACKER_MAC, GATEWAY, "192.168.1.20"))
    assert spoof["kind"] == "arp_spoof"
    assert spoof["src_mac"] == ATTACKER_MAC
    assert spoof["detail"] == {"claimed_ip": GATEWAY, "previous_mac": GATEWAY_MAC}


def test_an_address_reused_after_its_owner_left_is_not_spoofing() -> None:
    monitor = ArpMonitor({OWN_MAC}, GATEWAY)
    monitor.observe(T, (2, "aa:bb:cc:00:00:02", "192.168.1.50", GATEWAY))
    assert monitor.observe(T + 10 * 60, (2, "aa:bb:cc:00:00:03", "192.168.1.50", GATEWAY)) == []
    assert monitor.observe(T + 10 * 60 + 1, (2, "aa:bb:cc:00:00:03", "192.168.1.50", GATEWAY)) == []


def test_router_advertisement_source_address() -> None:
    advertisement = bytes([134, 0, 0, 0, 64, 0, 0x07, 0x08]) + b"\x00" * 8
    advertisement += bytes([5, 1, 0, 0, 0, 0, 0x05, 0xDC])
    advertisement += bytes([1, 1]) + mac_bytes(GATEWAY_MAC)
    assert router_advertisement_mac(advertisement) == GATEWAY_MAC
    assert router_advertisement_mac(bytes([135]) + advertisement[1:]) is None
    assert router_advertisement_mac(advertisement[:16] + bytes([1, 0])) is None


def test_dhcp_offer_server_identifier() -> None:
    xid = 0x12345678
    offer = struct.pack("!BBBBI", 2, 1, 6, 0, xid) + b"\x00" * 228 + b"\x63\x82\x53\x63"
    offer += bytes([53, 1, 2, 54, 4]) + socket.inet_aton(GATEWAY) + bytes([255])
    assert dhcp_offer_server(offer, xid) == GATEWAY
    assert dhcp_offer_server(offer, xid + 1) is None
    ack = offer.replace(bytes([53, 1, 2]), bytes([53, 1, 5]))
    assert dhcp_offer_server(ack, xid) is None


def test_poisoning_answers_are_recognised() -> None:
    query = _dns_query("backup-1234", 0x4242)
    assert len(query) == 12 + 1 + len("backup-1234") + 1 + 4
    answer = struct.pack("!HHHHHH", 0x4242, 0x8000, 1, 1, 0, 0) + query[12:]
    assert dns_has_answer(answer, 0x4242)
    assert not dns_has_answer(answer, 0x4243)
    assert not dns_has_answer(query, 0x4242)
    nbns = _nbns_query("backup-1234", 0x4242)
    assert len(nbns) == 12 + 34 + 4
    assert nbns_has_answer(struct.pack("!HHHHHH", 0x4242, 0x8500, 0, 1, 0, 0), 0x4242)


def test_newcomers_learns_first_then_reports() -> None:
    assert newcomers([], {GATEWAY}, GATEWAY) == ([GATEWAY], [])
    assert newcomers([GATEWAY], {GATEWAY}, GATEWAY) == ([GATEWAY], [])
    assert newcomers([GATEWAY], {GATEWAY, "192.168.1.66"}, GATEWAY) == ([GATEWAY], ["192.168.1.66"])
    assert newcomers([], {GATEWAY, "192.168.1.66"}, GATEWAY) == ([GATEWAY], ["192.168.1.66"])
    assert newcomers([], {"192.168.1.2"}, GATEWAY) == (["192.168.1.2"], [])
    assert newcomers([], set(), GATEWAY) == ([], [])
