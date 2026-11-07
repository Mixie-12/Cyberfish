"""Decoy services: which ones are configured and which ones are listening,
and the user names in the bait files.

Used by the agent for its heartbeat, and run as a script by the canary
container's health check. Standard library only, because it runs in both
images.
"""

import json
import sys
from pathlib import Path

TCP_LISTEN = "0A"

# The file share is Samba's smbd (canary/persona/smb.conf), not an OpenCanary
# module; it runs in the same container.
FILE_SHARE_PORT = 445
FILE_SHARE_SERVICE = "smb"


def load_decoys(config_path: Path) -> dict[int, str]:
    """Map port to service name for every decoy.

    The decoys are the services enabled in an OpenCanary config, and the file share.
    """
    config = json.loads(Path(config_path).read_text())
    decoys = {FILE_SHARE_PORT: FILE_SHARE_SERVICE}
    for key, value in config.items():
        name, _, field = key.partition(".")
        port = config.get(f"{name}.port")
        if field == "enabled" and value is True and isinstance(port, int):
            decoys[port] = name
    return decoys


def load_bait_users(persona_path: Path) -> frozenset[str]:
    """The user names written into the file share's bait files, in lower case.

    They are in nothing else, so a sign-in with one means someone read the bait.
    """
    persona = json.loads(Path(persona_path).read_text(encoding="utf-8"))
    return frozenset(name.casefold() for name in persona["bait_users"].values())


def listening_ports(proc_net: Path = Path("/proc/net")) -> set[int]:
    """TCP ports in LISTEN state in the current network namespace."""
    ports = set()
    for table in ("tcp", "tcp6"):
        path = proc_net / table
        if not path.exists():
            continue
        for line in path.read_text().splitlines()[1:]:
            fields = line.split()
            if fields[3] == TCP_LISTEN:
                ports.add(int(fields[1].rsplit(":", 1)[1], 16))
    return ports


def running_services(decoys: dict[int, str], ports: set[int]) -> list[str]:
    return sorted(name for port, name in decoys.items() if port in ports)


if __name__ == "__main__":
    sys.exit(0 if set(load_decoys(Path(sys.argv[1]))) <= listening_ports() else 1)
