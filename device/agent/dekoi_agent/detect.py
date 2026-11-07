"""Port scan detection and collapsing of repeated events."""

from .sources import iso_time

SCAN_WINDOW_SECONDS = 60
SCAN_MIN_PORTS = 5
MAX_REPORTED_PORTS = 64
MAX_TRACKED_SOURCES = 4096
COLLAPSE_SECONDS = 60
# Conditions that persist rather than happen once: one alert, then an hourly
# count while they continue.
LONG_COLLAPSE_SECONDS = 60 * 60
PERSISTENT_KINDS = {"arp_spoof", "rogue_dhcp", "rogue_router", "name_poisoning"}


class ScanDetector:
    """Reports a port scan when one source probes enough closed ports quickly."""

    def __init__(self) -> None:
        self._ports: dict[str, dict[int, float]] = {}

    def observe(self, at: float, src_ip: str, src_mac: str | None, port: int) -> dict | None:
        if src_ip not in self._ports and len(self._ports) >= MAX_TRACKED_SOURCES:
            return None
        ports = self._ports.setdefault(src_ip, {})
        ports[port] = at
        if len(ports) < SCAN_MIN_PORTS:
            return None
        del self._ports[src_ip]
        event = {
            "occurred_at": iso_time(at),
            "kind": "port_scan",
            "src_ip": src_ip,
            "count": len(ports),
            "detail": {"ports": sorted(ports)[:MAX_REPORTED_PORTS]},
        }
        if src_mac:
            event["src_mac"] = src_mac
        return event

    def expire(self, at: float) -> None:
        for src_ip in list(self._ports):
            ports = self._ports[src_ip]
            for port in [p for p, seen in ports.items() if at - seen > SCAN_WINDOW_SECONDS]:
                del ports[port]
            if not ports:
                del self._ports[src_ip]


class Collapser:
    """Sends the first event of a kind from a source at once, then counts repeats.

    Repeats within the window are reported as one event whose count is the
    number of repeats, so a brute-force run does not become thousands of rows
    and the first alert is never delayed.
    """

    def __init__(self) -> None:
        self._windows: dict[tuple, dict] = {}

    def observe(self, at: float, event: dict) -> dict | None:
        key = (event["kind"], event.get("service"), event.get("src_ip"))
        window = self._windows.get(key)
        if window is None:
            length = (
                LONG_COLLAPSE_SECONDS if event["kind"] in PERSISTENT_KINDS else COLLAPSE_SECONDS
            )
            self._windows[key] = {"until": at + length, "repeats": 0, "event": event}
            return event
        window["repeats"] += event.get("count", 1)
        return None

    def flush(self, at: float) -> list[dict]:
        closed = []
        for key, window in list(self._windows.items()):
            if at < window["until"]:
                continue
            del self._windows[key]
            if window["repeats"]:
                closed.append(
                    window["event"] | {"count": window["repeats"], "occurred_at": iso_time(at)}
                )
        return closed
