"""The agent loop: collect events, register, deliver, report status."""

import logging
import secrets
import subprocess
import time
from collections.abc import Callable

from . import api, inventory
from .config import Config
from .decoys import listening_ports, load_decoys, running_services
from .detect import Collapser, ScanDetector
from .sources import FileFollower, parse_canary, parse_scan
from .state import State

log = logging.getLogger(__name__)

BATCH_SIZE = 200
HEARTBEAT_SECONDS = 60
INVENTORY_SECONDS = 15 * 60
REGISTER_SECONDS = 30
MAX_BACKOFF_SECONDS = 5 * 60
REMOVED_BACKOFF_SECONDS = 60 * 60


class Schedule:
    """When an action is next due: a fixed interval, doubling after failures."""

    def __init__(self, interval: float) -> None:
        self.interval = interval
        self.delay = interval
        self.due = 0.0

    def ready(self, at: float) -> bool:
        return at >= self.due

    def succeeded(self, at: float) -> None:
        self.delay = self.interval
        self.due = at + self.interval

    def failed(self, at: float, ceiling: float = MAX_BACKOFF_SECONDS) -> None:
        self.delay = min(max(self.delay * 2, 1), ceiling)
        self.due = at + self.delay


class Agent:
    def __init__(self, config: Config, state: State, clock: Callable[[], float] = time.time):
        self.config = config
        self.state = state
        self.clock = clock
        self.decoys = load_decoys(config.decoy_config)
        self.canary = FileFollower(config.canary_log, _position(state, "canary"))
        self.scans = FileFollower(config.scan_log, _position(state, "scan"))
        self.detector = ScanDetector()
        self.collapser = Collapser()
        self.register_schedule = Schedule(REGISTER_SECONDS)
        self.send_schedule = Schedule(0)
        self.heartbeat_schedule = Schedule(HEARTBEAT_SECONDS)
        self.inventory_schedule = Schedule(INVENTORY_SECONDS)

    def tick(self) -> None:
        at = self.clock()
        self.collect(at)
        if self.state.device_id is None:
            if self.register_schedule.ready(at):
                self.register(at)
            return
        if self.state.queue and self.send_schedule.ready(at):
            self.send(at)
        if self.heartbeat_schedule.ready(at):
            self.heartbeat(at)
        if self.inventory_schedule.ready(at):
            self.inventory(at)

    def collect(self, at: float) -> None:
        events = self.collapser.flush(at)
        for line in self.canary.read_lines():
            event = parse_canary(line, self.decoys, at)
            if event and "src_mac" not in event:
                mac = inventory.mac_for(event["src_ip"])
                if mac:
                    event["src_mac"] = mac
            if event and (kept := self.collapser.observe(at, event)):
                events.append(kept)
        for line in self.scans.read_lines():
            probe = parse_scan(line)
            event = self.detector.observe(*probe) if probe else None
            if event and (kept := self.collapser.observe(at, event)):
                events.append(kept)
        self.detector.expire(at)
        positions = {"canary": self.canary.position, "scan": self.scans.position}
        positions = {name: list(p) for name, p in positions.items() if p is not None}
        if not events and positions == self.state.positions:
            return
        self.state.positions = positions
        dropped = self.state.enqueue(events)
        if dropped:
            log.warning("queue full, dropped %d oldest events", dropped)
        self.save()

    def register(self, at: float) -> None:
        try:
            claim_code = self.config.claim_code_path.read_text().strip()
        except OSError as error:
            log.error("cannot read claim code: %s", error)
            self.register_schedule.failed(at)
            return
        if self.state.secret is None:
            self.state.secret = secrets.token_hex(32)
            self.save()
        arguments = {
            "claim_code": claim_code,
            "secret": self.state.secret,
            "agent_version": self.config.agent_version,
        }
        try:
            device_id = api.call(self.config, "device_register_v1", arguments)
        except api.ApiError as error:
            if error.refused:
                log.info("waiting for this device to be claimed in the app")
            elif error.malformed:
                log.error("backend rejected the claim code on this card as malformed")
            else:
                log.warning("registration failed: %s", error)
            self.register_schedule.failed(at)
            return
        self.state.device_id = str(device_id)
        self.save()
        log.info("registered as device %s", self.state.device_id)

    def send(self, at: float) -> None:
        batch = self.state.queue[:BATCH_SIZE]
        try:
            api.call(self.config, "device_events_v1", self._auth() | {"events": batch})
        except api.ApiError as error:
            if not error.malformed:
                self._failed(self.send_schedule, at, error)
                return
            log.error("backend rejected %d events as malformed, dropping them", len(batch))
        del self.state.queue[: len(batch)]
        self.save()
        self.send_schedule.succeeded(at)

    def heartbeat(self, at: float) -> None:
        arguments = self._auth() | {
            "agent_version": self.config.agent_version,
            "services": running_services(self.decoys, listening_ports()),
            "local_ip": inventory.local_ip(self.config.api_url),
        }
        try:
            api.call(self.config, "device_heartbeat_v1", arguments)
        except api.ApiError as error:
            self._failed(self.heartbeat_schedule, at, error)
            return
        self.heartbeat_schedule.succeeded(at)

    def inventory(self, at: float) -> None:
        try:
            hosts = inventory.scan_hosts()
            api.call(self.config, "device_hosts_v1", self._auth() | {"hosts": hosts})
        except api.ApiError as error:
            self._failed(self.inventory_schedule, at, error)
            return
        except (OSError, subprocess.SubprocessError) as error:
            log.warning("network scan failed: %s", error)
            self.inventory_schedule.failed(at, ceiling=INVENTORY_SECONDS)
            return
        self.inventory_schedule.succeeded(at)

    def save(self) -> None:
        self.state.save(self.config.state_path)

    def _auth(self) -> dict:
        return {"device_id": self.state.device_id, "secret": self.state.secret}

    def _failed(self, schedule: Schedule, at: float, error: api.ApiError) -> None:
        if error.refused:
            log.error("backend refused this device; it may have been removed in the app")
            schedule.failed(at, ceiling=REMOVED_BACKOFF_SECONDS)
            return
        log.warning("backend call failed: %s", error)
        schedule.failed(at)


def _position(state: State, name: str) -> tuple[int, int] | None:
    position = state.positions.get(name)
    return (position[0], position[1]) if position else None
