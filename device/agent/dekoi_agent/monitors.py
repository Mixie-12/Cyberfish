"""What the device watches, how each watcher is doing, and which images run
(ADR 0013). The heartbeat carries both.

One rule judges every monitor: it is ok while its source has shown life
recently enough, starting when it has not yet and the agent has only just
started or registered, and failed otherwise. Where a source shows life, and for how long
that counts, is decided by the agent at the place it reads the source.

The IDS describes itself in Suricata's sensor name, which Suricata adds to
every record it sends, including the statistics it sends every minute:
"dekoi-ids <version> <build time> <ruleset date>" (ids/start.sh). The other
images carry "<version> <build time>" in IMAGE_FILE; the canary copies its
file into the log volume when it starts (canary/Containerfile).
"""

import json
import os
import stat
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

NAMES = (
    "decoys",
    "port_scan",
    "arp_watch",
    "dhcp_watch",
    "router_watch",
    "name_poisoning",
    "ids",
    "inventory",
)
# Longer than the IDS takes to start after the agent, load its rules and send
# its first statistics (over four minutes in a test on a PC).
STARTUP_SECONDS = 15 * 60
# Suricata sends statistics every 60 seconds (ids/suricata.yaml). The window
# also covers a restart for an update, which loads the rules again.
IDS_SECONDS = 10 * 60
IDS_SENSOR = "dekoi-ids"
IMAGE_FILE = Path("/usr/share/dekoi/image")
MAX_IMAGE_FILE = 256
MAX_COUNT = 9_999_999
MAX_VERSION = 32
VERSION_CHARACTERS = frozenset("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz.+-")
UTC_SECOND = "%Y-%m-%dT%H:%M:%SZ"
DAY = "%Y-%m-%d"


@dataclass
class Monitors:
    started_at: float
    # When each monitor's evidence of life runs out.
    until: dict[str, float] = field(default_factory=dict)
    details: dict[str, dict[str, int | str]] = field(default_factory=dict)
    ids_image: dict[str, str] | None = None

    def alive(self, name: str, until: float, **detail: int | str) -> None:
        self.until[name] = until
        if detail:
            self.details[name] = detail

    def decoys(self, at: float, running: int, configured: int) -> None:
        self.details["decoys"] = {"decoys": running}
        if configured and running == configured:
            self.until["decoys"] = at

    def ids(self, at: float, lines: list[str]) -> None:
        """Any record from Suricata shows it is running; its statistics
        add the rule count and its sensor name the image and ruleset."""
        for line in lines:
            try:
                record = json.loads(line)
            except ValueError:
                continue
            if not isinstance(record, dict):
                continue
            self.until["ids"] = at + IDS_SECONDS
            if record.get("event_type") == "stats":
                self._ids_stats(record)

    def _ids_stats(self, record: dict) -> None:
        detail: dict[str, int | str] = {}
        rules = _rules_loaded(record.get("stats"))
        if rules is not None:
            detail["rules"] = rules
        sensor = record.get("host")
        fields = sensor.split(" ") if isinstance(sensor, str) else []
        image = _image(fields[1:3]) if len(fields) == 4 and fields[0] == IDS_SENSOR else None
        self.ids_image = image if image and _valid_time(fields[3], DAY) else None
        if self.ids_image:
            detail["ruleset_date"] = fields[3]
        self.details["ids"] = detail

    def report(self, at: float) -> list[dict]:
        return [self._monitor(name, at) for name in NAMES]

    def _monitor(self, name: str, at: float) -> dict:
        if name in self.until and at <= self.until[name]:
            state = "ok"
        elif name not in self.until and at < self.started_at + STARTUP_SECONDS:
            state = "starting"
        else:
            state = "failed"
        monitor: dict[str, object] = {"name": name, "state": state}
        if self.details.get(name):
            monitor["detail"] = self.details[name]
        return monitor

    def images(self, canary_file: Path, agent_file: Path = IMAGE_FILE) -> list[dict]:
        found = {
            "canary": read_image(canary_file),
            "agent": read_image(agent_file),
            "ids": self.ids_image,
        }
        return [{"name": name} | image for name, image in found.items() if image]


def read_image(path: Path) -> dict[str, str] | None:
    """The version and build time an image file holds, or None.

    The canary's file lies in a volume the decoys can write: a link planted
    there is not followed, a pipe cannot block the agent, and only the first
    bytes of a regular file are read.
    """
    try:
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    except OSError:
        return None
    with os.fdopen(descriptor, "rb") as handle:
        if not stat.S_ISREG(os.fstat(descriptor).st_mode):
            return None
        text = handle.read(MAX_IMAGE_FILE).decode("ascii", "replace")
    return _image(text.split())


def _image(fields: list[str]) -> dict[str, str] | None:
    if len(fields) != 2:
        return None
    version, updated_at = fields
    if not (0 < len(version) <= MAX_VERSION and set(version) <= VERSION_CHARACTERS):
        return None
    if not _valid_time(updated_at, UTC_SECOND):
        return None
    return {"version": version, "updated_at": updated_at}


def _valid_time(text: str, layout: str) -> bool:
    """Exactly in layout and a real date; strptime alone accepts unpadded
    fields, so the result is formatted back and compared."""
    try:
        return datetime.strptime(text, layout).strftime(layout) == text
    except ValueError:
        return False


def _rules_loaded(stats: object) -> int | None:
    """Rules loaded by Suricata's detection engines (stats.detect.engines)."""
    detect = stats.get("detect") if isinstance(stats, dict) else None
    engines = detect.get("engines") if isinstance(detect, dict) else None
    if not isinstance(engines, list) or not engines:
        return None
    counts = [engine.get("rules_loaded") for engine in engines if isinstance(engine, dict)]
    if len(counts) != len(engines) or not all(type(count) is int for count in counts):
        return None
    total = sum(counts)
    return total if 0 <= total <= MAX_COUNT else None
