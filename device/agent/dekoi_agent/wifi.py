"""Wi-Fi watch (ADR 0019): access points that copy the home network's name.

The host scans with iw every five minutes while this module asks for it by
writing the regulatory country, and leaves the result in SCAN_PATH: a status
word on the first line, then iw's text (device/host/usr/local/libexec/
dekoi-wifi-scan). Everything in that text that came over the air is
attacker-controlled; it is parsed here, unprivileged, and bounded.

For the first 24 hours of scans with a home name the watch learns the access
points that carry it. After that, one with the name and weaker security than
the weakest learned is an evil twin (critical). A new one with the same or
better security is reported once as a new access point (warning): mesh
systems and new routers are common, and a copy that knows the password is
indistinguishable from them.
"""

import json
import logging
import os
import re
from dataclasses import asdict, dataclass, field
from pathlib import Path

from .sources import clean_text, iso_time

log = logging.getLogger(__name__)

SCAN_PATH = Path("/run/dekoi/wifi/scan")
COUNTRY_PATH = Path("/run/dekoi/wifi-country/country")
CHECK_SECONDS = 5 * 60
LEARNING_SECONDS = 24 * 60 * 60
# Three missed scans; also how long the host may take to start scanning
# after the country is first written.
STALE_SECONDS = 15 * 60
# The host stores at most this much of iw's output after the status line.
MAX_SCAN_BYTES = 1 << 20
MAX_ACCESS_POINTS = 512
MAX_SSID_BYTES = 32
MAX_KNOWN = 64
# At most this many access points are reported per 24 hours, so a flood of
# fake access points with the home name raises a handful of alerts, not
# thousands.
MAX_ALERTS = 16
ALERT_SECONDS = 24 * 60 * 60
# Weakest first. WPA is WPA version 1; an access point accepting several is
# as weak as the weakest it accepts.
SECURITY = ("open", "wep", "wpa", "wpa2", "wpa3")
# The assumption while nothing has been learned: the common home setup.
DEFAULT_HOME_SECURITY = "wpa2"
# Authentication suites as iw names them that are WPA3 (SAE and Suite B;
# iw 6.9 prints SAE-EXT-KEY and FT/SAE-EXT-KEY as raw suite numbers), and
# OWE, which encrypts but lets anyone join.
WPA3_SUITES = {
    "SAE",
    "FT/SAE",
    "00-0f-ac:24",
    "00-0f-ac:25",
    "802.1X/SUITE-B",
    "802.1X/SUITE-B-192",
}
OPEN_SUITES = {"OWE"}
# Channels 5 MHz apart: (first MHz, last MHz, MHz of channel 0) for 2.4, 5
# and 6 GHz, and the channels outside that pattern.
BANDS = ((2412, 2472, 2407), (5160, 5885, 5000), (5955, 7115, 5950))
ODD_CHANNELS = {2484: 14, 5935: 2}

_BSS = re.compile(r"BSS ((?:[0-9a-f]{2}:){5}[0-9a-f]{2})[( ]")
_FREQUENCY = re.compile(r"([0-9]{4,5})(?:\.[0-9]+)?")
_SIGNAL = re.compile(r"(-?[0-9]{1,3})\.")
_ESCAPE = re.compile(r"\\x([0-9a-f]{2})")
_COUNTRY = re.compile(r"[A-Z]{2}")


@dataclass(frozen=True)
class AccessPoint:
    bssid: str
    ssid: str
    security: str
    channel: int | None
    signal_dbm: int | None


def parse_scan(text: str, complete: bool = True) -> list[AccessPoint]:
    """Access points in `iw dev wlan0 scan` output.

    Lines are split on newline only, as iw writes them. An incomplete scan
    (cut at the size limit) loses its last access point, whose security
    may not have been printed yet.
    """
    blocks: list[list[str]] = []
    for line in text.split("\n"):
        if line.startswith("BSS "):
            blocks.append([line])
        elif blocks:
            blocks[-1].append(line)
    if not complete:
        blocks = blocks[:-1]
    found = (_access_point(block) for block in blocks[:MAX_ACCESS_POINTS])
    return [ap for ap in found if ap is not None]


def _access_point(block: list[str]) -> AccessPoint | None:
    """One BSS. The kernel's own fields (frequency, capability, signal) come
    before the information elements and only their first occurrence counts.
    A BSS without a name or capability is incomplete and ignored."""
    match = _BSS.match(block[0])
    if match is None:
        return None
    fields: dict[str, str] = {}
    # One list of authentication suites per RSN or WPA element, in order.
    elements: list[tuple[str, list[str]]] = []
    for raw in block[1:]:
        line = raw.strip()
        key, colon, value = line.partition(":")
        if not colon:
            continue
        if key in ("freq", "capability", "signal", "SSID"):
            fields.setdefault(key, value.strip())
        elif key in ("RSN", "WPA", "OSEN"):
            elements.append((key, []))
        elif key == "* Authentication suites" and elements:
            elements[-1][1].extend(value.replace("IEEE 802.1X", "802.1X").split())
    if "SSID" not in fields or "capability" not in fields:
        return None
    return AccessPoint(
        bssid=match.group(1),
        ssid=decode_ssid(fields["SSID"]),
        security=_security("Privacy" in fields["capability"].split(), elements),
        channel=_channel(fields.get("freq", "")),
        signal_dbm=_signal(fields.get("signal", "")),
    )


def _security(privacy: bool, elements: list[tuple[str, list[str]]]) -> str:
    """The weakest security the access point accepts. Without the privacy
    bit, which the kernel reports before any element, it is open whatever
    the elements claim."""
    if not privacy:
        return "open"
    if not elements:
        return "wep"
    return min((_element_security(*element) for element in elements), key=SECURITY.index)


def _element_security(element: str, suites: list[str]) -> str:
    if element == "WPA":
        return "wpa"
    if not suites:
        return "wpa2"
    return min((_suite_security(suite) for suite in suites), key=SECURITY.index)


def _suite_security(suite: str) -> str:
    if suite in WPA3_SUITES:
        return "wpa3"
    if suite in OPEN_SUITES:
        return "open"
    return "wpa2"


def _channel(frequency: str) -> int | None:
    match = _FREQUENCY.fullmatch(frequency)
    if match is None:
        return None
    mhz = int(match.group(1))
    if mhz in ODD_CHANNELS:
        return ODD_CHANNELS[mhz]
    for first, last, base in BANDS:
        if first <= mhz <= last:
            return (mhz - base) // 5
    return None


def _signal(signal: str) -> int | None:
    match = _SIGNAL.match(signal)
    if match is None or not signal.endswith(" dBm"):
        return None
    dbm = int(match.group(1))
    return dbm if -120 <= dbm <= 0 else None


def decode_ssid(printed: str) -> str:
    """The network name as people see it.

    iw prints every SSID byte outside printable ASCII, and the backslash, as
    \\xNN. The bytes are decoded as UTF-8, bounded to an SSID's 32 bytes, and
    cleaned like every other attacker-controlled string: control and
    invisible characters and surrounding blanks go, so a copy that only adds
    those still has the same name.
    """
    raw = bytearray()
    for index, part in enumerate(_ESCAPE.split(printed)):
        raw += bytes([int(part, 16)]) if index % 2 else part.encode()
    return clean_text(raw[:MAX_SSID_BYTES].decode("utf-8", errors="replace"))


def home_name(value: object) -> str | None:
    """The owner's network name from the backend, cleaned the same way."""
    if not isinstance(value, str):
        return None
    name = clean_text(value)
    return name if name and len(name.encode()) <= MAX_SSID_BYTES else None


@dataclass
class Learned:
    """What the watch keeps across restarts, in a file of its own."""

    name: str | None = None
    # When the first scan with this name arrived; learning lasts a day from then.
    since: float | None = None
    # Access points learned or already reported, with their security.
    known: dict[str, str] = field(default_factory=dict)
    # When each access point was last reported.
    alerted: dict[str, float] = field(default_factory=dict)
    # As reported to the backend; the vocabulary of devices.wifi_watch.
    status: str = "no_name"


class Watch:
    def __init__(
        self, path: Path, scan_path: Path = SCAN_PATH, country_path: Path = COUNTRY_PATH
    ) -> None:
        self.path = path
        self.scan_path = scan_path
        self.country_path = country_path
        self.learned = _load(path)

    @property
    def status(self) -> str:
        return self.learned.status

    def check(self, at: float, reply: object) -> list[dict]:
        """Applies the reply of device_wifi_v1 and reads the latest scan.

        Returns the events to report. The status says why the watch is off,
        if it is: no home name, a card from before the watch (no shared
        directories), no country, or a radio that is in use or not scanning.
        """
        before = asdict(self.learned)
        name = home_name(reply.get("name")) if isinstance(reply, dict) else None
        country = reply.get("country") if isinstance(reply, dict) else None
        if not (isinstance(country, str) and _COUNTRY.fullmatch(country)):
            country = None
        if name != self.learned.name:
            self.learned = Learned(name=name)
        events: list[dict] = []
        if name is None:
            self._ask_for_scans(None)
            self.learned.status = "no_name"
        elif not (self.scan_path.parent.is_dir() and self.country_path.parent.is_dir()):
            self.learned.status = "no_radio"
        elif country is None:
            self._ask_for_scans(None)
            self.learned.status = "no_country"
        else:
            self._ask_for_scans(country)
            events = self._read_scan(at, name)
        if asdict(self.learned) != before:
            self._save()
        return events

    def _ask_for_scans(self, country: str | None) -> None:
        """The host scans only while the country file exists."""
        try:
            if country is None:
                self.country_path.unlink(missing_ok=True)
            elif _read_text(self.country_path) != country + "\n":
                _write_atomically(self.country_path, country + "\n", 0o644)
        except OSError as error:
            log.warning("cannot write the Wi-Fi country: %s", error)

    def _read_scan(self, at: float, name: str) -> list[dict]:
        try:
            with self.scan_path.open("rb") as handle:
                modified = os.fstat(handle.fileno()).st_mtime
                status, _, body = handle.read(MAX_SCAN_BYTES + 64).partition(b"\n")
        except FileNotFoundError:
            modified, status, body = 0.0, b"", b""
        except OSError as error:
            log.warning("cannot read the Wi-Fi scan: %s", error)
            modified, status, body = 0.0, b"", b""
        if at - modified > STALE_SECONDS:
            # Not scanning (yet): the host starts within one timer period of
            # the country appearing.
            self.learned.status = "no_radio" if self._asked_long_ago(at) else self._learning(at)
            return []
        if status in (b"uplink", b"no_radio"):
            self.learned.status = status.decode()
            return []
        if status != b"scan":
            log.warning("unknown Wi-Fi scan status, ignoring the scan")
            self.learned.status = "no_radio"
            return []
        text = body.decode("utf-8", errors="replace")
        home = [ap for ap in parse_scan(text, len(body) < MAX_SCAN_BYTES) if ap.ssid == name]
        if self.learned.since is None:
            self.learned.since = at
        self.learned.status = self._learning(at)
        if self.learned.status == "learning":
            for ap in home:
                self._remember(ap)
            return []
        return self._compare(at, home)

    def _asked_long_ago(self, at: float) -> bool:
        try:
            return at - self.country_path.stat().st_mtime > STALE_SECONDS
        except OSError:
            return True

    def _learning(self, at: float) -> str:
        since = self.learned.since
        return "learning" if since is None or at - since < LEARNING_SECONDS else "watching"

    def _remember(self, ap: AccessPoint) -> None:
        known = self.learned.known
        if ap.bssid in known:
            known[ap.bssid] = min(known[ap.bssid], ap.security, key=SECURITY.index)
        elif len(known) < MAX_KNOWN:
            known[ap.bssid] = ap.security

    def _compare(self, at: float, home: list[AccessPoint]) -> list[dict]:
        learned = self.learned
        floor = min(learned.known.values(), key=SECURITY.index, default=DEFAULT_HOME_SECURITY)
        learned.alerted = {
            bssid: when for bssid, when in learned.alerted.items() if at - when < ALERT_SECONDS
        }
        events = []
        for ap in home:
            if ap.bssid in learned.alerted or len(learned.alerted) >= MAX_ALERTS:
                continue
            weaker = SECURITY.index(ap.security) < SECURITY.index(floor)
            if not weaker and ap.bssid in learned.known:
                continue
            if not weaker:
                self._remember(ap)
            learned.alerted[ap.bssid] = at
            events.append(_event(at, "evil_twin" if weaker else "new_access_point", ap, floor))
        return events

    def _save(self) -> None:
        _write_atomically(self.path, json.dumps(asdict(self.learned)), 0o600)


def _event(at: float, kind: str, ap: AccessPoint, home_security: str) -> dict:
    detail: dict[str, object] = {"security": ap.security, "home_security": home_security}
    if ap.channel is not None:
        detail["channel"] = ap.channel
    if ap.signal_dbm is not None:
        detail["signal_dbm"] = ap.signal_dbm
    return {"occurred_at": iso_time(at), "kind": kind, "src_mac": ap.bssid, "detail": detail}


def _load(path: Path) -> Learned:
    """A damaged file starts learning again rather than stopping the agent."""
    try:
        return Learned(**json.loads(path.read_text()))
    except FileNotFoundError:
        return Learned()
    except (OSError, ValueError, TypeError) as error:
        log.warning("Wi-Fi watch state unreadable, learning again: %s", error)
        return Learned()


def _read_text(path: Path) -> str | None:
    try:
        return path.read_text()
    except FileNotFoundError:
        return None


def _write_atomically(path: Path, text: str, mode: int) -> None:
    temporary = path.with_name(path.name + ".tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, mode)
    with os.fdopen(descriptor, "w") as handle:
        handle.write(text)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, path)
