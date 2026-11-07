"""Agent state that must survive restarts, stored as one JSON file."""

import json
import os
from dataclasses import asdict, dataclass, field
from pathlib import Path

MAX_QUEUE = 5000


@dataclass
class State:
    device_id: str | None = None
    secret: str | None = None
    next_event_id: int = 1
    positions: dict[str, list[int]] = field(default_factory=dict)
    queue: list[dict] = field(default_factory=list)
    # Learned legitimate infrastructure (lan.newcomers).
    dhcp_servers: list[str] = field(default_factory=list)
    routers: list[str] = field(default_factory=list)

    @classmethod
    def load(cls, path: Path) -> "State":
        if not path.exists():
            return cls()
        return cls(**json.loads(path.read_text()))

    def save(self, path: Path) -> None:
        """Write atomically; the file holds the device secret, so 0600."""
        temporary = path.with_name(path.name + ".tmp")
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "w") as handle:
            json.dump(asdict(self), handle)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)

    def enqueue(self, events: list[dict]) -> int:
        """Number each event and queue it. Returns how many old events were dropped."""
        for event in events:
            self.queue.append(event | {"agent_event_id": self.next_event_id})
            self.next_event_id += 1
        dropped = max(0, len(self.queue) - MAX_QUEUE)
        del self.queue[:dropped]
        return dropped
