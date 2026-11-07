import re
from pathlib import Path

import pytest

from dekoi_agent.classify import RULES, classify

MIGRATION = (
    Path(__file__).parents[3]
    / "backend"
    / "supabase"
    / "migrations"
    / "20261107160000_device_identification.sql"
)


def vocabulary(column: str) -> set[str]:
    sql = MIGRATION.read_text()
    match = re.search(rf"add column {column} text[^(]*check \(\s*{column} in \(([^)]*)\)", sql)
    assert match is not None, column
    return set(re.findall(r"'([a-z_]+)'", match.group(1)))


def test_rules_use_only_the_backend_vocabularies() -> None:
    kinds, systems = vocabulary("kind"), vocabulary("os")
    assert "unknown" in kinds
    assert {kind for _, _, kind, _, _ in RULES if kind} <= kinds
    assert {os for _, _, _, os, _ in RULES if os} <= systems


@pytest.mark.parametrize(
    ("evidence", "expected"),
    [
        (
            {"name.dhcp": ["Karis-iPhone"], "service": ["companion-link", "apple-mobdev2"]},
            ("phone", "ios"),
        ),
        (
            {
                "name.dhcp": ["LGwebOSTV"],
                "vendor": ["LG Electronics"],
                "upnp": [
                    "urn:lge-com:service:webos-second-screen:1",
                    "WebOS/4.1.0 UPnP/1.0 webOSTV/1.0",
                ],
            },
            ("tv", "webos"),
        ),
        (
            {"name.dhcp": ["Galaxy-S23"], "dhcp_class": ["android-dhcp-14"]},
            ("phone", "android"),
        ),
        (
            {"name.dhcp": ["Galaxy-Tab-S8"], "dhcp_class": ["android-dhcp-13"]},
            ("tablet", "android"),
        ),
        (
            {
                "name.mdns": ["HPA1B2C3"],
                "service": ["ipp", "ipps", "uscan", "http"],
                "model.mdns": ["HP LaserJet Pro M404dn"],
                "vendor": ["HP Inc."],
            },
            ("printer", None),
        ),
        (
            {"name.dhcp": ["BRWA0B1C2D3E4F5"], "vendor": ["Brother Industries, LTD."]},
            ("printer", None),
        ),
        (
            {
                "name.dhcp": ["BRAVIA-4K-GB"],
                "upnp": ["urn:schemas-sony-com:service:ScalarWebAPI:1"],
                "vendor": ["Sony Corporation"],
            },
            ("tv", None),
        ),
        (
            {
                "name.dhcp": ["DESKTOP-4F2K9QX"],
                "name.netbios": ["DESKTOP-4F2K9QX"],
                "dhcp_class": ["MSFT 5.0"],
                "dhcp_params": ["1,3,6,15,31,33,43,44,46,47,119,121,249,252"],
            },
            ("computer", "windows"),
        ),
        (
            {
                "upnp": [
                    "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
                    "urn:schemas-upnp-org:device:WANDevice:1",
                    "urn:schemas-upnp-org:device:WLANAccessPointDevice:1",
                    "FRITZ!Box 7590 UPnP/1.0 AVM FRITZ!Box 7590 154.07.57",
                ],
                "vendor": ["AVM Audiovisuelles Marketing und Computersysteme GmbH"],
            },
            ("router", None),
        ),
        (
            {
                "lldp": ["bridge"],
                "name.lldp": ["USW-Lite-8-PoE"],
                "model.lldp": ["USW-Lite-8-PoE, 7.0.50.15613"],
                "vendor": ["Ubiquiti Inc"],
            },
            ("switch", None),
        ),
        ({"lldp": ["bridge,wlan-ap,router"]}, ("router", None)),
        ({"model.mdns": ["AppleTV6,2"], "service": ["airplay", "raop"]}, ("media_player", "tvos")),
        ({"service": ["googlecast"], "model.mdns": ["Chromecast Ultra"]}, ("media_player", None)),
        ({"service": ["sonos", "spotify-connect"]}, ("speaker", None)),
        ({"name.dhcp": ["android-5f2a9c0e4b1d7a3e"]}, ("unknown", "android")),
        ({"vendor": ["Espressif Inc."], "name.dhcp": ["ESP-1A2B3C"]}, ("smart_home", None)),
        ({"vendor": ["Raspberry Pi Trading Ltd"]}, ("computer", "linux")),
    ],
)
def test_real_world_hosts(evidence: dict[str, list[str]], expected: tuple) -> None:
    assert classify(evidence) == expected


@pytest.mark.parametrize(
    "evidence",
    [
        {},
        {"vendor": ["Hewlett Packard"]},
        {"vendor": ["Samsung Electronics Co.,Ltd"]},
        {"vendor": ["Apple, Inc."], "service": ["companion-link", "airplay"]},
        {"vendor": ["TP-LINK TECHNOLOGIES CO.,LTD."]},
        {"service": ["googlecast"]},
        {"name.dhcp": ["kitchen"], "upnp": ["urn:schemas-upnp-org:device:MediaRenderer:1"]},
    ],
)
def test_weak_or_no_evidence_stays_unknown(evidence: dict[str, list[str]]) -> None:
    assert classify(evidence)[0] == "unknown"


def test_two_weak_signals_that_agree_are_enough() -> None:
    evidence = {
        "vendor": ["Western Digital"],
        "upnp": ["urn:schemas-upnp-org:device:MediaServer:1"],
    }
    assert classify(evidence) == ("storage", None)


def test_strong_signals_that_disagree_leave_the_host_unknown() -> None:
    evidence = {"vendor": ["Raspberry Pi Trading Ltd"], "service": ["home-assistant"]}
    assert classify(evidence) == ("unknown", "linux")
