"""Consistency between the files that together define a device.

Host configuration is frozen on the card while images update, so a mismatch
here would ship to every device written afterwards.
"""

import json
import re
from pathlib import Path

from dekoi_agent.decoys import FILE_SHARE_PORT, load_decoys

DEVICE = Path(__file__).parents[1]
HOST = DEVICE / "host"
NFTABLES = HOST / "etc" / "nftables.conf"
PERSONA = DEVICE / "canary" / "persona"
USER_DATA = DEVICE / "cloud-init" / "user-data.yaml"
INCLUDE = re.compile(r"^\s+content: !file (\S+)$", re.MULTILINE)

# The announcements (dekoi-announce), on the ports their protocols define:
# NetBIOS name and datagram service (nmbd), WS-Discovery (wsdd2, which also
# serves its metadata over TCP) and mDNS (avahi-daemon).
ANNOUNCEMENT_UDP_PORTS = {137, 138, 3702, 5353}
ANNOUNCEMENT_TCP_PORTS = {3702}


def nft_set(name: str) -> set[int]:
    match = re.search(
        rf"set {name} \{{.*?elements = \{{([^}}]*)\}}", NFTABLES.read_text(), re.DOTALL
    )
    assert match is not None, name
    return {int(port) for port in match.group(1).split(",")}


def test_firewall_opens_exactly_the_decoys_and_their_announcements() -> None:
    decoys = set(load_decoys(DEVICE / "canary" / "opencanary.conf"))
    assert nft_set("decoy_tcp_ports") == decoys | ANNOUNCEMENT_TCP_PORTS
    assert nft_set("decoy_udp_ports") == ANNOUNCEMENT_UDP_PORTS


def test_web_decoy_also_answers_on_port_5000() -> None:
    config = json.loads((DEVICE / "canary" / "opencanary.conf").read_text())
    redirects = re.findall(r"tcp dport (\d+) redirect to :(\d+)", NFTABLES.read_text())
    assert redirects == [("5000", str(config["http.port"]))]


def test_file_share_listens_where_the_agent_expects_it() -> None:
    smb_conf = (PERSONA / "smb.conf").read_text()
    assert re.search(r"^\s+smb ports = (\d+)$", smb_conf, re.MULTILINE)[1] == str(FILE_SHARE_PORT)


def test_host_name_is_the_persona_name() -> None:
    name = json.loads((PERSONA / "persona.json").read_text())["name"]
    assert re.search(r"^hostname: (\S+)$", USER_DATA.read_text(), re.MULTILINE)[1] == name
    # A NetBIOS name has at most 15 characters; a host name label is
    # lower-case letters, digits and hyphens.
    assert re.fullmatch(r"[a-z0-9][a-z0-9-]{0,14}", name)


def test_every_included_host_file_exists() -> None:
    includes = INCLUDE.findall(USER_DATA.read_text())
    assert includes
    missing = [path for path in includes if not (DEVICE / path).is_file()]
    assert missing == []


def test_every_host_file_is_installed_by_first_boot() -> None:
    installed = set(INCLUDE.findall(USER_DATA.read_text()))
    present = {path.relative_to(DEVICE).as_posix() for path in HOST.rglob("*") if path.is_file()}
    assert present == installed


def test_every_unit_is_started_by_first_boot() -> None:
    started = re.search(r"\[systemctl, start, ([^\]]*)\]", USER_DATA.read_text())
    assert started is not None
    units = {unit.stem + ".service" for unit in (HOST / "etc" / "containers" / "systemd").iterdir()}
    assert set(started[1].split(", ")) == units


def test_images_come_from_the_signed_namespace() -> None:
    policy = json.loads((HOST / "etc" / "containers" / "policy.json").read_text())
    assert policy["default"] == [{"type": "reject"}]
    (namespace,) = policy["transports"]["docker"]
    for unit in (HOST / "etc" / "containers" / "systemd").glob("*.container"):
        image = re.search(r"^Image=(\S+)$", unit.read_text(), re.MULTILINE)
        assert image is not None
        assert image.group(1).startswith(namespace + "/"), unit.name
        assert image.group(1).endswith(":stable"), unit.name
