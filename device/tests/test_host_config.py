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
POLICY = HOST / "etc" / "containers" / "policy.json"
INCLUDE = re.compile(r"^\s+content: !file (\S+)$", re.MULTILINE)
WRITE_FILE = re.compile(
    r"^\s+- path: (\S+)\n(?:\s+\w+: .*\n)*?\s+content: !file (\S+)$", re.MULTILINE
)

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


def test_every_host_file_lands_at_its_own_path() -> None:
    text = USER_DATA.read_text()
    entries = WRITE_FILE.findall(text)
    assert len(entries) == len(INCLUDE.findall(text))
    assert [(path, source) for path, source in entries if source != "host" + path] == []


def test_every_unit_is_started_by_first_boot() -> None:
    started = re.search(r"\[systemctl, start, ([^\]]*)\]", USER_DATA.read_text())
    assert started is not None
    units = {unit.stem + ".service" for unit in (HOST / "etc" / "containers" / "systemd").iterdir()}
    assert set(started[1].split(", ")) == units


def test_memory_limits_do_not_stop_a_container_on_a_pi() -> None:
    # Raspberry Pi firmware disables the memory cgroup controller; a Podman
    # --memory limit then keeps the container from starting at all.
    units = (HOST / "etc" / "containers" / "systemd").glob("*.container")
    limited = [
        unit.name
        for unit in units
        if re.search(r"^PodmanArgs=.*--memory", unit.read_text(), re.MULTILINE)
    ]
    assert not limited
    user_data = USER_DATA.read_text()
    assert "cgroup_enable=memory" in user_data
    assert re.search(r"^power_state:\n  mode: reboot$", user_data, re.MULTILINE)


def test_a_hung_agent_restarts_before_the_backend_counts_it_offline() -> None:
    unit = (HOST / "etc" / "containers" / "systemd" / "dekoi-agent.container").read_text()
    watchdog = re.search(r"^WatchdogSec=(\d+)min$", unit, re.MULTILINE)
    assert watchdog is not None
    # private.mark_offline_devices: 30 minutes without a heartbeat.
    assert 10 <= int(watchdog[1]) < 30
    assert re.search(r"^Notify=true$", unit, re.MULTILINE)
    assert 'notify("WATCHDOG=1")' in (DEVICE / "agent" / "dekoi_agent" / "__main__.py").read_text()


def test_updates_install_where_first_boot_replaced_a_package_file() -> None:
    # Without both, unattended-upgrades holds back every update that brings
    # a new version of /etc/nftables.conf, /etc/ulogd.conf or policy.json.
    apt = (HOST / "etc" / "apt" / "apt.conf.d" / "52dekoi").read_text()
    options = r'^DPkg::Options \{\n\t"--force-confdef";\n\t"--force-confold";\n\};$'
    assert re.search(options, apt, re.MULTILINE)


def test_devices_trust_only_the_release_repositories() -> None:
    policy = json.loads(POLICY.read_text())
    assert policy["default"] == [{"type": "reject"}]
    images = set()
    for unit in (HOST / "etc" / "containers" / "systemd").glob("*.container"):
        image = re.search(r"^Image=(\S+):stable$", unit.read_text(), re.MULTILINE)
        assert image is not None, unit.name
        images.add(image.group(1))
    assert set(policy["transports"]["docker"]) == images


def test_every_trusted_key_is_on_the_card() -> None:
    policy = json.loads(POLICY.read_text())
    for requirements in policy["transports"]["docker"].values():
        (requirement,) = requirements
        assert requirement["type"] == "sigstoreSigned"
        assert requirement["signedIdentity"] == {"type": "matchRepository"}
        assert requirement["keyPaths"]
        for path in requirement["keyPaths"]:
            assert (HOST / path.removeprefix("/")).is_file(), path
