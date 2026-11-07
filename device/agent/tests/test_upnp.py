import re
import threading
from collections.abc import Iterator
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import ClassVar

import pytest

from dekoi_agent.upnp import (
    description_url,
    parse_mapping,
    port_mappings,
    wan_connections,
)

GATEWAY = "192.168.1.1"
IGD = "urn:schemas-upnp-org:device:InternetGatewayDevice:1"
WAN_IP = "urn:schemas-upnp-org:service:WANIPConnection:1"


def description(control: str, base: str | None = None, service: str = WAN_IP) -> bytes:
    url_base = f"<URLBase>{base}</URLBase>" if base else ""
    return (
        '<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0">'
        f"{url_base}<device><deviceType>{IGD}</deviceType><deviceList><device>"
        "<deviceType>urn:schemas-upnp-org:device:WANDevice:1</deviceType><deviceList><device>"
        "<serviceList><service>"
        "<serviceType>urn:schemas-upnp-org:service:WANCommonInterfaceConfig:1</serviceType>"
        "<controlURL>/ctl/CommonIfCfg</controlURL></service><service>"
        f"<serviceType>{service}</serviceType><controlURL>{control}</controlURL>"
        "</service></serviceList></device></deviceList></device></deviceList></device></root>"
    ).encode()


def entry(
    port: str = "3074",
    client: str = "192.168.1.40",
    protocol: str = "UDP",
    enabled: str = "1",
    text: str = "Xbox (3074) &amp; party",
) -> bytes:
    return (
        '<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">'
        f'<s:Body><u:GetGenericPortMappingEntryResponse xmlns:u="{WAN_IP}">'
        f"<NewRemoteHost></NewRemoteHost><NewExternalPort>{port}</NewExternalPort>"
        f"<NewProtocol>{protocol}</NewProtocol><NewInternalPort>{port}</NewInternalPort>"
        f"<NewInternalClient>{client}</NewInternalClient><NewEnabled>{enabled}</NewEnabled>"
        f"<NewPortMappingDescription>{text}</NewPortMappingDescription>"
        "<NewLeaseDuration>0</NewLeaseDuration>"
        "</u:GetGenericPortMappingEntryResponse></s:Body></s:Envelope>"
    ).encode()


def test_only_the_gateways_own_igd_description_is_taken() -> None:
    location = ("location", f"http://{GATEWAY}:5000/rootDesc.xml")
    assert description_url([("upnp", IGD), location], GATEWAY) == location[1]
    assert description_url([("upnp", WAN_IP), location], GATEWAY) == location[1]
    media_server = [("upnp", "urn:schemas-upnp-org:device:MediaServer:1"), location]
    assert description_url(media_server, GATEWAY) is None, "another device on the router"
    for elsewhere in (
        "http://192.168.1.66:5000/rootDesc.xml",
        f"https://{GATEWAY}/rootDesc.xml",
        f"http://{GATEWAY}@192.168.1.66/rootDesc.xml",
        f"http://{GATEWAY}:99999/rootDesc.xml",
        f"http://{GATEWAY}/" + "a" * 300,
        f"http://{GATEWAY}/root\nDesc.xml",
    ):
        assert description_url([("upnp", IGD), ("location", elsewhere)], GATEWAY) is None


def test_wan_connections_are_found_at_the_gateway_only() -> None:
    location = f"http://{GATEWAY}:5000/rootDesc.xml"
    assert wan_connections(description("/ctl/IPConn"), location, GATEWAY) == [
        (WAN_IP, 5000, "/ctl/IPConn")
    ]
    assert wan_connections(
        description("ctl/IPConn", base=f"http://{GATEWAY}:49000/"), location, GATEWAY
    ) == [(WAN_IP, 49000, "/ctl/IPConn")]
    assert wan_connections(description("//192.168.1.66/ctl"), location, GATEWAY) == []
    assert wan_connections(description("http://example.org/ctl"), location, GATEWAY) == []
    ppp = "urn:schemas-upnp-org:service:WANPPPConnection:1"
    assert wan_connections(description("/ppp", service=ppp), location, GATEWAY) == [
        (ppp, 5000, "/ppp")
    ]


def test_mappings_are_checked_before_they_are_reported() -> None:
    assert parse_mapping(entry()) == {
        "protocol": "udp",
        "external_port": 3074,
        "internal_client": "192.168.1.40",
        "internal_port": 3074,
        "description": "Xbox (3074) & party",
    }
    assert parse_mapping(entry(enabled="0")) is None
    assert parse_mapping(entry(protocol="ICMP")) is None
    assert parse_mapping(entry(port="70000")) is None
    assert parse_mapping(entry(port="\u0661\u0662")) is None, "digits, but not ASCII ones"
    assert parse_mapping(entry(client="camera.local")) is None
    cleaned = parse_mapping(entry(text="evil\u202e\u200b" + "x" * 100))
    assert cleaned is not None
    assert cleaned["description"] == "evil" + "x" * 60
    assert parse_mapping(entry(text=""))["description"] is None


def test_documents_with_a_document_type_or_another_encoding_are_refused() -> None:
    laughs = (
        b'<?xml version="1.0"?><!DOCTYPE l [<!ENTITY a "aaaaaaaaaa">'
        b'<!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">]>' + entry().replace(b"Xbox", b"&b;")
    )
    with pytest.raises(ValueError, match="document type"):
        parse_mapping(laughs)
    with pytest.raises(ValueError):
        parse_mapping(entry().decode().encode("utf-16"))


class Gateway(BaseHTTPRequestHandler):
    """A UPnP gateway: its description, then the mapping table entry by entry."""

    entries: ClassVar[list[bytes]] = []
    actions: ClassVar[list[str]] = []
    description = b""

    def do_GET(self) -> None:
        self._answer(200, Gateway.description)

    def do_POST(self) -> None:
        length = int(self.headers["Content-Length"])
        index = int(re.search(rb"<NewPortMappingIndex>(\d+)<", self.rfile.read(length))[1])
        Gateway.actions.append(self.headers["SOAPAction"])
        if index < len(Gateway.entries):
            self._answer(200, Gateway.entries[index])
        else:
            self._answer(500, b"<s:Envelope><errorCode>713</errorCode></s:Envelope>")

    def _answer(self, status: int, body: bytes) -> None:
        self.send_response(status)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args: object) -> None:
        pass


@pytest.fixture
def gateway(monkeypatch: pytest.MonkeyPatch) -> Iterator[int]:
    """A gateway on 127.0.0.1; returns its port."""
    server = ThreadingHTTPServer(("127.0.0.1", 0), Gateway)
    port = server.server_address[1]
    monkeypatch.setattr(Gateway, "actions", [])
    monkeypatch.setattr(Gateway, "description", description(f"http://127.0.0.1:{port}/ctl"))
    thread = threading.Thread(target=server.serve_forever)
    thread.start()
    yield port
    server.shutdown()
    server.server_close()
    thread.join()


def test_the_mapping_table_is_read_until_the_gateway_says_it_ends(
    gateway: int, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(
        Gateway,
        "entries",
        [entry(), entry(enabled="0"), entry(port="8080", protocol="TCP", text=""), entry()],
    )
    found = port_mappings(f"http://127.0.0.1:{gateway}/rootDesc.xml", "127.0.0.1")
    assert [(m["protocol"], m["external_port"]) for m in found] == [("udp", 3074), ("tcp", 8080)]
    assert set(Gateway.actions) == {f'"{WAN_IP}#GetGenericPortMappingEntry"'}, "reads only"
    assert len(Gateway.actions) == 5


def test_a_table_that_cannot_be_read_is_not_reported_as_empty(
    gateway: int, monkeypatch: pytest.MonkeyPatch
) -> None:
    url = f"http://127.0.0.1:{gateway}/rootDesc.xml"
    monkeypatch.setattr(Gateway, "entries", [b"x" * 20_000])
    assert port_mappings(url, "127.0.0.1") is None, "too large"
    monkeypatch.setattr(Gateway, "entries", [b"<not-xml"])
    assert port_mappings(url, "127.0.0.1") is None
    monkeypatch.setattr(Gateway, "description", description("/ctl", service="urn:other:1"))
    assert port_mappings(url, "127.0.0.1") is None, "no WAN connection"
    assert port_mappings(url, "192.168.1.1") is None, "not the gateway"
    assert port_mappings(None, "127.0.0.1") is None
