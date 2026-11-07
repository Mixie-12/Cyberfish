"""Reading the decoy and firewall logs and turning them into events.

Everything in these logs is attacker-controlled. Only known fields are taken,
strings are bounded and stripped of control and formatting characters, and
captured passwords are never read.
"""

import ipaddress
import json
import unicodedata
from datetime import UTC, datetime
from pathlib import Path

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

SCAN_PREFIX = "dekoi-scan"

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
