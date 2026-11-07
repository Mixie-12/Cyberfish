"""Consistency between the files that together define a device.

Host configuration is frozen on the card while images update, so a mismatch
here would ship to every device written afterwards.
"""

import json
import re
from pathlib import Path

from dekoi_agent.decoys import load_decoys

DEVICE = Path(__file__).parents[1]
HOST = DEVICE / "host"
USER_DATA = DEVICE / "cloud-init" / "user-data.yaml"
INCLUDE = re.compile(r"^\s+content: !file (\S+)$", re.MULTILINE)


def test_firewall_opens_exactly_the_enabled_decoys() -> None:
    nftables = (HOST / "etc" / "nftables.conf").read_text()
    match = re.search(r"set decoy_tcp_ports \{.*?elements = \{([^}]*)\}", nftables, re.DOTALL)
    assert match is not None
    ports = {int(port) for port in match.group(1).split(",")}
    assert ports == set(load_decoys(DEVICE / "canary" / "opencanary.conf"))


def test_every_included_host_file_exists() -> None:
    includes = INCLUDE.findall(USER_DATA.read_text())
    assert includes
    missing = [path for path in includes if not (DEVICE / path).is_file()]
    assert missing == []


def test_every_host_file_is_installed_by_first_boot() -> None:
    installed = set(INCLUDE.findall(USER_DATA.read_text()))
    present = {path.relative_to(DEVICE).as_posix() for path in HOST.rglob("*") if path.is_file()}
    assert present == installed


def test_images_come_from_the_signed_namespace() -> None:
    policy = json.loads((HOST / "etc" / "containers" / "policy.json").read_text())
    assert policy["default"] == [{"type": "reject"}]
    (namespace,) = policy["transports"]["docker"]
    for unit in (HOST / "etc" / "containers" / "systemd").glob("*.container"):
        image = re.search(r"^Image=(\S+)$", unit.read_text(), re.MULTILINE)
        assert image is not None
        assert image.group(1).startswith(namespace + "/"), unit.name
        assert image.group(1).endswith(":stable"), unit.name
