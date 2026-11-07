"""Reading the decoy and firewall logs and turning them into events.

Everything in these logs is attacker-controlled. Only known fields are taken,
strings are bounded and stripped of control and formatting characters, and
captured passwords are never read.
"""

import ipaddress
import json
import os
import socket
import unicodedata
from datetime import UTC, datetime
from pathlib import Path

from .decoys import FILE_SHARE_PORT, FILE_SHARE_SERVICE

MAX_READ_BYTES = 1 << 20
MAX_LINE_BYTES = 1 << 16
MAX_TEXT = 256

# OpenCanary log types (opencanary/logger.py) mapped to event kinds. Types not
# listed are ignored.
CANARY_KINDS = {
    2000: "login_attempt",  # FTP login
    3000: "connection",  # HTTP GET
    3001: "login_attempt",  # HTTP login form
    4000: "connection",  # SSH connection
    4002: "login_attempt",  # SSH login
    6001: "login_attempt",  # Telnet login
    8001: "login_attempt",  # MySQL login
    12001: "login_attempt",  # VNC authentication
    14001: "login_attempt",  # RDP connection with user name
    17001: "connection",  # Redis command
}

# OpenCanary logdata fields we keep, and their name in the event detail.
# PASSWORD and command arguments are deliberately absent.
CANARY_DETAIL = {
    "USERNAME": "username",
    "PATH": "path",
    "USERAGENT": "user_agent",
    "REMOTEVERSION": "client_version",
    "CMD": "command",
}

# Samba's audit log (canary/persona/smb.conf). A full_audit record is
# "client|share|share path|operation|result|argument". Clients list the
# shares through IPC$, which is browsing and never an event.
SAMBA_FILE_READS = {"pread", "pread_recv"}
SAMBA_SHARE_LIST = "IPC$"

SCAN_PREFIX = "dekoi-scan"

# Suricata severity: 1 high, 2 medium, 3 low. Low-severity alerts are mostly
# informational and stay on the device.
MAX_IDS_SEVERITY = 2

# Unicode categories removed from attacker text: control, format (zero-width,
# bidirectional overrides), surrogate, private use, unassigned, and line and
# paragraph separators.
_STRIPPED_CATEGORIES = {"Cc", "Cf", "Cs", "Co", "Cn", "Zl", "Zp"}


def clean_text(value: str) -> str:
    kept = (c for c in value if unicodedata.category(c) not in _STRIPPED_CATEGORIES)
    return "".join(kept).strip()[:MAX_TEXT]


def iso_time(at: float) -> str:
    return datetime.fromtimestamp(at, UTC).isoformat()


def valid_ip(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    try:
        return str(ipaddress.ip_address(value))
    except ValueError:
        return None


def valid_port(value: object) -> int | None:
    return value if isinstance(value, int) and 0 <= value <= 65535 else None


def _canary_time(value: object, now: float) -> str:
    try:
        parsed = datetime.strptime(str(value), "%Y-%m-%d %H:%M:%S.%f")
    except ValueError:
        return iso_time(now)
    return parsed.replace(tzinfo=UTC).isoformat()


def parse_canary(line: str, decoys: dict[int, str], now: float) -> dict | None:
    """Turn one OpenCanary JSON log line into an event, or None to ignore it."""
    try:
        record = json.loads(line)
    except ValueError:
        return None
    if not isinstance(record, dict):
        return None
    kind = CANARY_KINDS.get(record.get("logtype"))
    src_ip = valid_ip(record.get("src_host"))
    if kind is None or src_ip is None:
        return None
    port = valid_port(record.get("dst_port"))
    logdata = record.get("logdata")
    logdata = logdata if isinstance(logdata, dict) else {}
    detail = {}
    for field, key in CANARY_DETAIL.items():
        value = logdata.get(field)
        text = clean_text(value) if isinstance(value, str) else ""
        if text:
            detail[key] = text
    event = {
        "occurred_at": _canary_time(record.get("utc_time"), now),
        "kind": kind,
        "service": decoys.get(port),
        "src_ip": src_ip,
        "dst_port": port,
        "detail": detail,
    }
    return {key: value for key, value in event.items() if value is not None}


def parse_samba(line: str, now: float) -> dict | None:
    """Turn one line of Samba's audit log into an event, or None to ignore it.

    A JSON authentication record is a sign-in attempt, unless the client sent
    no password at all: that is a client looking for anonymous access while
    browsing. Connecting to a share means listing its contents, and a read
    means someone opened a file. Samba logs the user name, never the password.
    """
    text = line.strip()
    if text.startswith("{"):
        return _samba_sign_in(text, now)
    return _samba_file_audit(text, now)


def _samba_sign_in(text: str, now: float) -> dict | None:
    try:
        record = json.loads(text)
    except ValueError:
        return None
    auth = record.get("Authentication") if isinstance(record, dict) else None
    if not isinstance(auth, dict) or auth.get("passwordType") == "No-Password":
        return None
    src_ip = _samba_address(auth.get("remoteAddress"))
    if src_ip is None:
        return None
    account = auth.get("clientAccount")
    return _samba_event(
        _samba_time(record.get("timestamp"), now),
        "login_attempt",
        src_ip,
        {"username": account},
    )


def _samba_file_audit(text: str, now: float) -> dict | None:
    fields = text.split("|", 5)
    if len(fields) != 6:
        return None
    client, share, share_path, operation, result, argument = fields
    src_ip = valid_ip(client)
    if src_ip is None or result != "ok":
        return None
    if operation == "connect" and argument != SAMBA_SHARE_LIST:
        return _samba_event(iso_time(now), "connection", src_ip, {"share": argument})
    if operation in SAMBA_FILE_READS:
        detail = {"share": share, "file": argument.removeprefix(share_path + "/")}
        return _samba_event(iso_time(now), "file_access", src_ip, detail)
    return None


def _samba_event(occurred_at: str, kind: str, src_ip: str, fields: dict[str, object]) -> dict:
    detail = {}
    for key, value in fields.items():
        text = clean_text(value) if isinstance(value, str) else ""
        if text:
            detail[key] = text
    return {
        "occurred_at": occurred_at,
        "kind": kind,
        "service": FILE_SHARE_SERVICE,
        "src_ip": src_ip,
        "dst_port": FILE_SHARE_PORT,
        "detail": detail,
    }


def _samba_address(value: object) -> str | None:
    """The IP address in a Samba address such as "ipv4:192.168.1.5:51234"."""
    if not isinstance(value, str):
        return None
    family, _, rest = value.partition(":")
    host = rest.rpartition(":")[0].partition("%")[0]
    return valid_ip(host) if family in ("ipv4", "ipv6") else None


def _samba_time(value: object, now: float) -> str:
    try:
        return datetime.strptime(str(value), "%Y-%m-%dT%H:%M:%S.%f%z").isoformat()
    except ValueError:
        return iso_time(now)


def parse_scan(line: str) -> tuple[float, str, str | None, int] | None:
    """Read one ulogd2 JSON line for a connection attempt to a closed port.

    Returns (time, source IP, source MAC, destination port), or None.
    """
    try:
        record = json.loads(line)
    except ValueError:
        return None
    if not isinstance(record, dict) or record.get("oob.prefix") != SCAN_PREFIX:
        return None
    at = record.get("oob.time.sec")
    src_ip = valid_ip(record.get("src_ip"))
    port = valid_port(record.get("dest_port"))
    if not isinstance(at, int) or src_ip is None or port is None:
        return None
    mac = record.get("mac.saddr.str")
    return float(at), src_ip, mac if isinstance(mac, str) else None, port


def parse_suricata(line: str, own_ips: set[str]) -> dict | None:
    """Turn one Suricata EVE JSON line into an ids_alert event, or None.

    Only rule metadata is kept (written by the ruleset authors); payload
    excerpts never leave the device. Alerts caused by DeKoi itself are
    ignored.
    """
    try:
        record = json.loads(line)
    except ValueError:
        return None
    if not isinstance(record, dict) or record.get("event_type") != "alert":
        return None
    alert = record.get("alert")
    src_ip = valid_ip(record.get("src_ip"))
    if not isinstance(alert, dict) or src_ip is None or src_ip in own_ips:
        return None
    severity = alert.get("severity")
    signature_id = alert.get("signature_id")
    if not isinstance(severity, int) or not 1 <= severity <= MAX_IDS_SEVERITY:
        return None
    detail = {"severity": severity}
    if isinstance(signature_id, int) and 0 <= signature_id < 10**10:
        detail["signature_id"] = signature_id
    for field in ("signature", "category"):
        value = alert.get(field)
        text = clean_text(value) if isinstance(value, str) else ""
        if text:
            detail[field] = text
    event = {
        "occurred_at": _suricata_time(record.get("timestamp")),
        "kind": "ids_alert",
        "src_ip": src_ip,
        "dst_port": valid_port(record.get("dest_port")),
        "detail": detail,
    }
    return {key: value for key, value in event.items() if value is not None}


def _suricata_time(value: object) -> str | None:
    try:
        return datetime.strptime(str(value), "%Y-%m-%dT%H:%M:%S.%f%z").isoformat()
    except ValueError:
        return None


class DatagramInbox:
    """A Unix datagram socket that Suricata sends its EVE records to.

    Suricata connects to it and reconnects when it reappears, so alerts
    need no log file and nothing has to be rotated. Records that arrive
    while the agent restarts are lost.
    """

    def __init__(self, path: Path) -> None:
        path.unlink(missing_ok=True)
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM)
        self.sock.bind(str(path))
        # Suricata runs as another unprivileged user and only needs to write.
        # Only the agent and IDS containers mount this volume.
        os.chmod(path, 0o622)  # noqa: S103
        self.sock.setblocking(False)

    def read_lines(self, limit: int = 1000) -> list[str]:
        lines = []
        for _ in range(limit):
            try:
                lines.append(self.sock.recv(MAX_LINE_BYTES).decode("utf-8", "replace"))
            except BlockingIOError:
                break
        return lines


class FileFollower:
    """Returns complete new lines of a log file that may be rotated or truncated.

    position is (inode, offset) of the next unread byte. None starts at the
    current end of the file, so old history is not replayed on first start.

    Rotation by rename is detected by the inode. Truncation in place is only
    detected when the file is shorter than what was already read, so writers
    must rotate by rename (OpenCanary does; logrotate is configured to).
    """

    def __init__(self, path: Path, position: tuple[int, int] | None) -> None:
        self.path = path
        self.position = position

    def read_lines(self) -> list[str]:
        try:
            stat = self.path.stat()
        except FileNotFoundError:
            return []
        inode, offset = self.position or (stat.st_ino, stat.st_size)
        if inode != stat.st_ino or stat.st_size < offset:
            offset = 0
        if stat.st_size == offset:
            self.position = (stat.st_ino, offset)
            return []
        with self.path.open("rb") as log:
            log.seek(offset)
            data = log.read(MAX_READ_BYTES)
        end = data.rfind(b"\n") + 1
        if end == 0:
            if len(data) < MAX_READ_BYTES:
                return []
            end = len(data)
        self.position = (stat.st_ino, offset + end)
        return [
            raw.decode("utf-8", "replace")
            for raw in data[:end].splitlines()
            if len(raw) <= MAX_LINE_BYTES
        ]
