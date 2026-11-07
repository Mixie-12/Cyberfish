"""Ports the router has opened to the internet through UPnP IGD (ADR 0015).

Read-only: the agent asks the gateway, at the gateway's own LAN address and
nowhere else, for its port mapping table (GetGenericPortMappingEntry) and
never adds, changes or removes a mapping. Where the gateway describes itself
comes from its SSDP answers and announcements, which the discovery listener
already reads (identify.parse_ssdp).

The gateway may be compromised, and any device on the network can ask it for
a mapping with any description. Responses are bounded in size, count and
time, parsed as UTF-8 without a document type, and every value is checked
before it is reported.
"""

import http.client
import ipaddress
import re
import time
import xml.etree.ElementTree as ET
from urllib.parse import urljoin, urlsplit

from .sources import clean_text

GATEWAY_TYPE = re.compile(
    r"^urn:schemas-upnp-org:(device:InternetGatewayDevice|service:WAN(IP|PPP)Connection):\d{1,2}$"
)
WAN_CONNECTION = re.compile(r"^urn:schemas-upnp-org:service:WAN(IP|PPP)Connection:\d{1,2}$")
HTTP_PORT = 80
TIMEOUT_SECONDS = 3
DEADLINE_SECONDS = 30
READ_SIZE = 4096
MAX_URL = 256
MAX_DESCRIPTION = 64 * 1024
MAX_RESPONSE = 16 * 1024
MAX_SERVICES = 4
MAX_PORT_DIGITS = 5
# The bounds of a device_health_v1 report and of port_forwards.description.
MAX_MAPPINGS = 64
MAX_DESCRIPTION_TEXT = 64
# A UPnP action error, such as 713 SpecifiedArrayIndexInvalid after the last entry.
UPNP_ERROR_STATUS = 500
PROTOCOLS = {"TCP": "tcp", "UDP": "udp"}
ENABLED = ("1", "true", "yes")
ENVELOPE = (
    '<?xml version="1.0"?>'
    '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"'
    ' s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">'
    '<s:Body><u:GetGenericPortMappingEntry xmlns:u="{service}">'
    "<NewPortMappingIndex>{index}</NewPortMappingIndex>"
    "</u:GetGenericPortMappingEntry></s:Body></s:Envelope>"
)

Target = tuple[int, str]


def description_url(facts: list[tuple[str, str]], gateway: str) -> str | None:
    """The description URL in an SSDP message about the gateway's IGD, if
    it points at the gateway itself."""
    if not any(field == "upnp" and GATEWAY_TYPE.match(value) for field, value in facts):
        return None
    return next(
        (value for field, value in facts if field == "location" and _on_gateway(value, gateway)),
        None,
    )


def port_mappings(url: str | None, gateway: str | None) -> list[dict] | None:
    """Every enabled mapping of the gateway's WAN connections, as
    device_health_v1 takes them, or None when they could not be read."""
    if not url or not gateway:
        return None
    location = _on_gateway(url, gateway)
    if location is None:
        return None
    deadline = time.monotonic() + DEADLINE_SECONDS
    found: dict[tuple, dict] = {}
    try:
        services = wan_connections(_get(gateway, location, deadline), url, gateway)
        if not services:
            return None
        for service, port, path in services:
            for mapping in _mappings(gateway, (port, path), service, deadline):
                key = (mapping["protocol"], mapping["external_port"], mapping["internal_client"])
                found.setdefault(key, mapping)
    except (OSError, http.client.HTTPException, ET.ParseError, ValueError):
        return None
    return list(found.values())[:MAX_MAPPINGS]


def wan_connections(description: bytes, location: str, gateway: str) -> list[tuple[str, int, str]]:
    """(service type, port, control path) of each WAN connection service a
    device description names at the gateway's address."""
    root = _parse(description)
    base = next(
        (e.text.strip() for e in root.iter() if _local(e.tag) == "URLBase" and e.text), location
    )
    found = []
    for service in root.iter():
        if _local(service.tag) != "service":
            continue
        fields = {_local(child.tag): (child.text or "").strip() for child in service}
        kind, control = fields.get("serviceType", ""), fields.get("controlURL", "")
        target = _on_gateway(urljoin(base, control), gateway) if control else None
        if WAN_CONNECTION.match(kind) and target:
            found.append((kind, *target))
    return found[:MAX_SERVICES]


def parse_mapping(response: bytes) -> dict | None:
    """One enabled mapping from a GetGenericPortMappingEntry response."""
    fields = {_local(e.tag): (e.text or "").strip() for e in _parse(response).iter()}
    if fields.get("NewEnabled", "").lower() not in ENABLED:
        return None
    mapping = {
        "protocol": PROTOCOLS.get(fields.get("NewProtocol", "").upper()),
        "external_port": _port(fields.get("NewExternalPort", ""), lowest=0),
        "internal_client": _ipv4(fields.get("NewInternalClient", "")),
        "internal_port": _port(fields.get("NewInternalPort", ""), lowest=1),
    }
    if None in mapping.values():
        return None
    text = clean_text(fields.get("NewPortMappingDescription", ""))[:MAX_DESCRIPTION_TEXT]
    return mapping | {"description": text.strip() or None}


def _get(gateway: str, target: Target, deadline: float) -> bytes:
    connection = http.client.HTTPConnection(gateway, target[0], timeout=TIMEOUT_SECONDS)
    try:
        connection.request("GET", target[1])
        response = connection.getresponse()
        body = _read(response, MAX_DESCRIPTION, deadline)
    finally:
        connection.close()
    if response.status != 200:
        raise ValueError(f"description answered {response.status}")
    return body


def _mappings(gateway: str, target: Target, service: str, deadline: float) -> list[dict]:
    """The mapping table of one service, entry by entry until the gateway
    says there are no more."""
    connection = http.client.HTTPConnection(gateway, target[0], timeout=TIMEOUT_SECONDS)
    found = []
    try:
        for index in range(MAX_MAPPINGS):
            connection.request(
                "POST",
                target[1],
                body=ENVELOPE.format(service=service, index=index).encode(),
                headers={
                    "Content-Type": 'text/xml; charset="utf-8"',
                    "SOAPAction": f'"{service}#GetGenericPortMappingEntry"',
                },
            )
            response = connection.getresponse()
            body = _read(response, MAX_RESPONSE, deadline)
            if response.status == UPNP_ERROR_STATUS:
                break
            if response.status != 200:
                raise ValueError(f"mapping {index} answered {response.status}")
            if mapping := parse_mapping(body):
                found.append(mapping)
    finally:
        connection.close()
    return found


def _read(response: http.client.HTTPResponse, limit: int, deadline: float) -> bytes:
    """The body, refused past limit bytes or the deadline. One receive per
    read, so a gateway that sends a byte at a time cannot stretch the time."""
    body = bytearray()
    while chunk := response.read1(READ_SIZE):
        body += chunk
        if len(body) > limit:
            raise ValueError("gateway response too large")
        if time.monotonic() > deadline:
            raise TimeoutError("gateway too slow")
    return bytes(body)


def _parse(document: bytes) -> ET.Element:
    # Entities can only be declared in a document type declaration, so
    # without one expat expands nothing but the predefined entities, whatever
    # its version. Text input makes expat read UTF-8 whatever encoding the
    # document declares, so the declaration cannot hide behind one.
    text = document.decode()
    if "<!DOCTYPE" in text:
        raise ValueError("document type declarations are refused")
    return ET.fromstring(text)  # noqa: S314 - bounded, UTF-8, no document type: see above


def _local(tag: str) -> str:
    """An element name without its namespace."""
    return tag.rpartition("}")[2]


def _on_gateway(url: str, gateway: str) -> Target | None:
    """Port and path of a plain HTTP URL at the gateway's address, else None."""
    if len(url) > MAX_URL or not url.isascii() or not url.isprintable():
        return None
    try:
        parts = urlsplit(url)
        port = parts.port or HTTP_PORT
    except ValueError:
        return None
    if parts.scheme != "http" or parts.hostname != gateway:
        return None
    path = parts.path or "/"
    return port, f"{path}?{parts.query}" if parts.query else path


def _port(text: str, lowest: int) -> int | None:
    if not (text.isascii() and text.isdigit() and len(text) <= MAX_PORT_DIGITS):
        return None
    port = int(text)
    return port if lowest <= port <= 65535 else None


def _ipv4(text: str) -> str | None:
    try:
        return str(ipaddress.IPv4Address(text))
    except ValueError:
        return None
