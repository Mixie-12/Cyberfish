"""The agent loop: collect events, register, deliver, report status."""

import logging
import secrets
import subprocess
import time
from collections.abc import Callable

from . import api, dns_check, health, identify, inventory, lan, monitors, upnp, wifi
from .config import Config
from .decoys import listening_ports, load_decoys, running_services
from .detect import Collapser, ScanDetector
from .sources import (
    DatagramInbox,
    FileFollower,
    iso_time,
    parse_canary,
    parse_samba,
    parse_scan,
    parse_suricata,
)
from .state import State

log = logging.getLogger(__name__)

BATCH_SIZE = 200
HEARTBEAT_SECONDS = 60
INVENTORY_SECONDS = 15 * 60
PROBE_SECONDS = 15 * 60
MEASURE_SECONDS = 60
HEALTH_SECONDS = 60 * 60
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
    def __init__(
        self,
        config: Config,
        state: State,
        clock: Callable[[], float] = time.time,
        listeners: lan.Listeners | None = None,
    ):
        self.config = config
        self.state = state
        self.clock = clock
        self.decoys = load_decoys(config.decoy_config)
        self.canary = FileFollower(config.canary_log, _position(state, "canary"))
        self.samba = FileFollower(config.samba_log, _position(state, "samba"))
        self.scans = FileFollower(config.scan_log, _position(state, "scan"))
        self.ids = DatagramInbox(config.ids_socket)
        self.listeners = listeners or lan.Listeners()
        self.arp = lan.ArpMonitor(lan.own_macs(), lan.default_gateway())
        self.detector = ScanDetector()
        self.collapser = Collapser()
        self.identities = identify.Identities()
        self.networks = inventory.SeenNetworks()
        self.register_schedule = Schedule(REGISTER_SECONDS)
        self.send_schedule = Schedule(0)
        self.heartbeat_schedule = Schedule(HEARTBEAT_SECONDS)
        self.inventory_schedule = Schedule(INVENTORY_SECONDS)
        self.probe_schedule = Schedule(PROBE_SECONDS)
        self.monitors = monitors.Monitors(clock())
        self.health = health.Health(*api.backend_address(config))
        self.integrity = dns_check.Integrity()
        self.gateway_description: str | None = None
        self.speed_test_wanted = False
        self.measure_schedule = Schedule(MEASURE_SECONDS)
        self.health_schedule = Schedule(HEALTH_SECONDS)
        self.wifi = wifi.Watch(config.state_path.with_name("wifi.json"))
        self.wifi_schedule = Schedule(wifi.CHECK_SECONDS)

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
        if self.probe_schedule.ready(at):
            self.probe(at)
        if self.measure_schedule.ready(at):
            self.measure(at)
        if self.health_schedule.ready(at):
            self.report_health(at)
        if self.wifi_schedule.ready(at):
            self.check_wifi(at)

    def collect(self, at: float) -> None:
        self._identify()
        events = self.collapser.flush(at) + self._keep(at, self._observe(at))
        followers = {"canary": self.canary, "samba": self.samba, "scan": self.scans}
        positions = {
            name: list(f.position) for name, f in followers.items() if f.position is not None
        }
        if not events and positions == self.state.positions:
            return
        self.state.positions = positions
        self._enqueue(events)

    def _observe(self, at: float) -> list[dict]:
        """Everything the passive sources noticed since the last tick."""
        found = [
            e for line in self.canary.read_lines() if (e := parse_canary(line, self.decoys, at))
        ]
        found += [e for line in self.samba.read_lines() if (e := parse_samba(line, at))]
        for line in self.scans.read_lines():
            probe = parse_scan(line)
            if probe and (event := self.detector.observe(*probe)):
                found.append(event)
        self.detector.expire(at)
        own_ips = {address for address in [inventory.local_ip()] if address}
        ids_lines = self.ids.read_lines()
        self.monitors.ids(at, ids_lines)
        found += [e for line in ids_lines if (e := parse_suricata(line, own_ips))]
        frames = self.listeners.arp_frames()
        if frames:
            # Every inventory scan puts ARP frames on the wire.
            self.monitors.alive("arp_watch", at + 2 * INVENTORY_SECONDS)
        for frame in frames:
            arp = lan.parse_arp(frame)
            if arp:
                found += self.arp.observe(at, arp)
        advertisements = self.listeners.router_advertisements()
        # Silence is normal here: many networks have no IPv6 router.
        self.monitors.alive("router_watch", at + HEARTBEAT_SECONDS)
        for router, _, prefixes in advertisements:
            for prefix in prefixes:
                self.networks.advertised(prefix, router)
        if advertisements:
            found += self._rogue_routers(at, [(ip, mac) for ip, mac, _ in advertisements])
        return found

    def _identify(self) -> None:
        """Records what discovery traffic says about its senders."""
        for frame in self.listeners.discovery_frames():
            found = identify.parse_frame(frame)
            if found is None or found[0] in self.arp.own_macs:
                continue
            mac, address, facts = found
            if address and address == self.arp.gateway_ip:
                self.gateway_description = (
                    upnp.description_url(facts, address) or self.gateway_description
                )
            self.identities.observe(mac, facts)
            self.networks.source_address(address)

    def _rogue_routers(self, at: float, advertisements: list[tuple[str, str]]) -> list[dict]:
        known, new = lan.newcomers(self.state.routers, {mac for _, mac in advertisements}, None)
        if known != self.state.routers:
            self.state.routers = known
            self.save()
        return [
            {
                "occurred_at": iso_time(at),
                "kind": "rogue_router",
                "src_ip": ip,
                "src_mac": mac,
                "detail": {"protocol": "ra"},
            }
            for ip, mac in advertisements
            if mac in new
        ]

    def _keep(self, at: float, found: list[dict]) -> list[dict]:
        """Adds the hardware address where the kernel knows it, then collapses repeats."""
        kept = []
        for event in found:
            self.networks.source_address(event.get("src_ip"))
            if "src_mac" not in event and "src_ip" in event:
                mac = inventory.mac_for(event["src_ip"])
                if mac:
                    event["src_mac"] = mac
            if first := self.collapser.observe(at, event):
                kept.append(first)
        return kept

    def _enqueue(self, events: list[dict]) -> None:
        dropped = self.state.enqueue(events)
        if dropped:
            log.warning("queue full, dropped %d oldest events", dropped)
        self.save()

    def probe(self, at: float) -> None:
        """Active checks: name poisoning and rogue DHCP servers."""
        found = []
        try:
            found += [
                {
                    "occurred_at": iso_time(at),
                    "kind": "name_poisoning",
                    "src_ip": ip,
                    "detail": {"protocol": protocol},
                }
                for ip, protocol in lan.probe_name_poisoning()
            ]
            self.monitors.alive("name_poisoning", at + 2 * PROBE_SECONDS)
            address = inventory.local_ip()
            target = inventory.scan_target(address) if address else None
            if target:
                servers = lan.probe_dhcp_servers(lan.interface_mac(target[0]))
                self.monitors.alive("dhcp_watch", at + 2 * PROBE_SECONDS)
                known, new = lan.newcomers(self.state.dhcp_servers, servers, lan.default_gateway())
                self.state.dhcp_servers = known
                found += [
                    {
                        "occurred_at": iso_time(at),
                        "kind": "rogue_dhcp",
                        "src_ip": server,
                        "detail": {"server_ip": server, "protocol": "dhcp"},
                    }
                    for server in new
                ]
        except OSError as error:
            log.warning("network probe failed: %s", error)
        self.probe_schedule.succeeded(at)
        self._enqueue(self._keep(at, found))

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
        # The probes and the inventory start only now.
        self.monitors.started_at = at
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
        services = running_services(self.decoys, listening_ports())
        self.monitors.decoys(at, len(services), len(self.decoys))
        if self.config.scan_log.exists():
            self.monitors.alive("port_scan", at)
        arguments = self._auth() | {
            "agent_version": self.config.agent_version,
            "services": services,
            "local_ip": inventory.local_ip(),
        }
        report = {
            "monitors": self.monitors.report(at),
            "images": self.monitors.images(self.config.canary_log.parent / "image"),
        }
        try:
            self._send_heartbeat(arguments, report)
        except api.ApiError as error:
            self._failed(self.heartbeat_schedule, at, error)
            return
        self.heartbeat_schedule.succeeded(at)

    def _send_heartbeat(self, arguments: dict, report: dict) -> None:
        """Version 2 with the monitoring report. Version 1 when the backend
        is older (404) or refuses the report (400), so the device stays
        online either way."""
        try:
            api.call(self.config, "device_heartbeat_v2", arguments | report)
        except api.ApiError as error:
            if not (error.missing or error.malformed):
                raise
            if error.malformed:
                log.error("backend rejected the monitoring report as malformed: %s", error)
            api.call(self.config, "device_heartbeat_v1", arguments)

    def inventory(self, at: float) -> None:
        """Hosts with what they say about themselves, and networks (ADR 0010).

        The service searches go out before the scan, so most answers have
        arrived when it ends."""
        try:
            lan.ask_for_services()
        except OSError as error:
            log.warning("service discovery search failed: %s", error)
        try:
            hosts = inventory.scan_hosts()
            self.monitors.alive("inventory", at + 2 * INVENTORY_SECONDS, hosts=len(hosts))
            self._identify()
            arguments = self._auth() | {
                "hosts": [self.identities.describe(host) for host in hosts],
                "networks": self.networks.report(inventory.routed_networks()),
            }
            api.call(self.config, "device_hosts_v2", arguments)
        except api.ApiError as error:
            self._failed(self.inventory_schedule, at, error)
            return
        except (OSError, subprocess.SubprocessError) as error:
            log.warning("network scan failed: %s", error)
            self.inventory_schedule.failed(at, ceiling=INVENTORY_SECONDS)
            return
        self.inventory_schedule.succeeded(at)

    def measure(self, at: float) -> None:
        """The minute's probes of the gateway, the resolver and the internet."""
        self.health.measure(at, lan.default_gateway(), dns_check.lan_resolver())
        self.measure_schedule.succeeded(at)

    def report_health(self, at: float) -> None:
        """The hourly network report (ADR 0015): probe aggregates, the DNS
        check, the gateway's port mappings and, at night while the owner wants
        one, a speed test. A failed report is retried with the next hour's."""
        resolver = dns_check.lan_resolver()
        arguments = self._auth() | {
            "samples": self.health.samples(at),
            "dns": self.integrity.check(resolver) if resolver else None,
            "resolver": resolver,
            "forwards": upnp.port_mappings(self.gateway_description, lan.default_gateway()),
            "download_mbps": (
                health.speed_test() if self.speed_test_wanted and health.night(at) else None
            ),
        }
        try:
            reply = api.call(self.config, "device_health_v1", arguments)
        except api.ApiError as error:
            if not error.malformed:
                log.warning("health report failed: %s", error)
                self.health_schedule.failed(at, ceiling=HEALTH_SECONDS)
                return
            log.error("backend rejected the health report as malformed, dropping it")
            reply = None
        self.health.reported()
        self.speed_test_wanted = isinstance(reply, dict) and reply.get("speed_test") is True
        self.health_schedule.succeeded(at)

    def check_wifi(self, at: float) -> None:
        """The Wi-Fi watch (ADR 0019): reports its state, gets its settings."""
        arguments = self._auth() | {"watch": self.wifi.status}
        try:
            reply = api.call(self.config, "device_wifi_v1", arguments)
        except api.ApiError as error:
            self._failed(self.wifi_schedule, at, error)
            return
        if events := self.wifi.check(at, reply):
            self._enqueue(events)
        self.wifi_schedule.succeeded(at)

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
