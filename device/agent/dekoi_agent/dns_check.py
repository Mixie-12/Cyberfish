"""Whether the network's resolver gives the same answers as an independent one
(ADR 0015).

Once an hour a few names whose addresses are the same from anywhere are
resolved twice: by the resolver the network hands out, and by DNS over HTTPS
at one fixed provider, reached by its address and checked against its
certificate, so neither the local resolver nor anything on the path can
change that side. Answers are compared per name and address family; only
addresses that point somewhere count, so a filter that blocks a name (no
answer, 0.0.0.0) is not mistaken for a hijack. Two or more names answered
with entirely different addresses is a mismatch; the agent reports it only
after two checks in a row found one.

Every reply is bounded and parsed with the same checks as the discovery
traffic (identify.dns_records).
"""

import http.client
import ipaddress
import secrets
import select
import socket
import ssl
import struct
import time
from pathlib import Path

from .identify import DNS_AAAA, dns_records
from .lan import DNS_A, dns_query

# Names on anycast addresses that have not changed in years.
NAMES = ("a.root-servers.net", "one.one.one.one", "dns.google")
RECORD_TYPES = (DNS_A, DNS_AAAA)
ADDRESS_LENGTHS = {DNS_A: 4, DNS_AAAA: 16}
# Cloudflare's resolver by address; its certificate names the address.
DOH_ADDRESS = "1.1.1.1"
DOH_PATH = "/dns-query"
DOH_MEDIA_TYPE = "application/dns-message"
DNS_PORT = 53
RECURSION_DESIRED = 0x0100
RESPONSE_FLAG = 0x8000
RCODE_MASK = 0x000F
# No error, and "no such name": the resolver answered.
ANSWERED_RCODES = (0, 3)
TIMEOUT_SECONDS = 2.0
MAX_MESSAGE = 4096
MAX_READS = 8
MAX_RESOLV_LINES = 64
MIN_DIFFERING_NAMES = 2
CHECKS_IN_A_ROW = 2

OK, MISMATCH, UNAVAILABLE = "ok", "mismatch", "unavailable"

Answer = frozenset[str] | None


def lan_resolver(path: Path = Path("/etc/resolv.conf")) -> str | None:
    """The first IPv4 name server the system uses."""
    try:
        lines = path.read_text().splitlines()[:MAX_RESOLV_LINES]
    except OSError:
        return None
    for line in lines:
        fields = line.split()
        if len(fields) < 2 or fields[0] != "nameserver":
            continue
        try:
            return str(ipaddress.IPv4Address(fields[1]))
        except ValueError:
            continue
    return None


def answers(message: bytes, query_id: int, record_type: int) -> Answer:
    """The addresses of record_type in a reply to our query: empty when the
    name has none, None when the message is not an answer to it."""
    if len(message) < 12:
        return None
    reply_id, flags, questions, count = struct.unpack("!HHHH", message[:8])
    if reply_id != query_id or not flags & RESPONSE_FLAG:
        return None
    if (flags & RCODE_MASK) not in ANSWERED_RCODES:
        return None
    try:
        records = dns_records(message, questions, count)
    except ValueError:
        return None
    length = ADDRESS_LENGTHS[record_type]
    return frozenset(
        str(ipaddress.ip_address(message[start:end]))
        for _, kind, start, end in records
        if kind == record_type and end - start == length
    )


def ask_lan(resolver: str, name: str, record_type: int, timeout: float = TIMEOUT_SECONDS) -> Answer:
    """One question to the network's resolver over UDP."""
    query_id = secrets.randbits(16)
    deadline = time.monotonic() + timeout
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            # Connected, so the kernel drops datagrams from any other address.
            sock.connect((resolver, DNS_PORT))
            sock.send(dns_query(name, query_id, record_type, RECURSION_DESIRED))
            for _ in range(MAX_READS):
                remaining = deadline - time.monotonic()
                if remaining <= 0 or not select.select([sock], [], [], remaining)[0]:
                    return None
                found = answers(sock.recv(MAX_MESSAGE), query_id, record_type)
                if found is not None:
                    return found
    except OSError:
        return None
    return None


def ask_doh(questions: list[tuple[str, int]]) -> list[Answer]:
    """The same questions over one verified HTTPS connection to the provider.
    After the first failure the remaining questions go unanswered."""
    connection = http.client.HTTPSConnection(
        DOH_ADDRESS, timeout=TIMEOUT_SECONDS, context=ssl.create_default_context()
    )
    found: list[Answer] = []
    try:
        for name, record_type in questions:
            found.append(_ask_doh(connection, name, record_type))
    except (OSError, http.client.HTTPException, ValueError):
        found += [None] * (len(questions) - len(found))
    finally:
        connection.close()
    return found


def _ask_doh(connection: http.client.HTTPSConnection, name: str, record_type: int) -> Answer:
    # RFC 8484 asks for ID 0; TLS, not the ID, protects the answer.
    connection.request(
        "POST",
        DOH_PATH,
        body=dns_query(name, 0, record_type, RECURSION_DESIRED),
        headers={"Content-Type": DOH_MEDIA_TYPE, "Accept": DOH_MEDIA_TYPE},
    )
    response = connection.getresponse()
    body = response.read(MAX_MESSAGE + 1)
    if response.status != 200 or len(body) > MAX_MESSAGE:
        raise ValueError(f"DNS over HTTPS answered {response.status}")
    return answers(body, 0, record_type)


def conclude(comparisons: list[tuple[str, Answer, Answer]]) -> str:
    """OK, MISMATCH or UNAVAILABLE from (name, local answer, independent
    answer) for each name and address family."""
    agreeing, differing = set(), set()
    for name, local, independent in comparisons:
        mine, theirs = _pointing(local), _pointing(independent)
        if mine and theirs:
            (agreeing if mine & theirs else differing).add(name)
    if len(differing) >= MIN_DIFFERING_NAMES:
        return MISMATCH
    return OK if agreeing else UNAVAILABLE


def _pointing(addresses: Answer) -> frozenset[str]:
    """Addresses that lead somewhere; filters answer 0.0.0.0 or :: to block."""
    return frozenset(a for a in addresses or () if not ipaddress.ip_address(a).is_unspecified)


class Integrity:
    """The hourly check, remembering how many checks in a row found a mismatch."""

    def __init__(self) -> None:
        self.mismatches = 0

    def check(self, resolver: str) -> str | None:
        """What to report: None while a mismatch is not yet confirmed."""
        questions = [(name, record_type) for name in NAMES for record_type in RECORD_TYPES]
        local = [ask_lan(resolver, name, record_type) for name, record_type in questions]
        independent = ask_doh(questions)
        result = conclude(
            [
                (name, mine, theirs)
                for (name, _), mine, theirs in zip(questions, local, independent, strict=True)
            ]
        )
        self.mismatches = self.mismatches + 1 if result == MISMATCH else 0
        if result == MISMATCH and self.mismatches < CHECKS_IN_A_ROW:
            return None
        return result
