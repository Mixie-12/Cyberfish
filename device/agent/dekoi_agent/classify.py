"""What kind of device a host is, and its operating system (ADR 0010).

One rules table. Evidence is a mapping from a field to the strings seen for
it; a field may carry a source after a dot ("name.dhcp"), which the rules
ignore. For each string, the first rule of its field that matches casts its
vote. A kind or os is chosen when it has at least one strong vote (or two
weak ones) and no rival has as many; otherwise the host stays unknown.

Kinds and operating systems are those of network_hosts.kind and .os in
backend/supabase/migrations/20261107160000_device_identification.sql; a test
holds the table to them.
"""

import re
from collections import Counter

STRONG = 2
WEAK = 1

# Fields:
#   lldp         enabled LLDP capabilities, for example "bridge,router,wlan-ap"
#   name         host names: DHCP, mDNS, NetBIOS, LLDP system name, mDNS instance
#   service      DNS-SD service labels, for example "airplay" or "ipp"
#   model        mDNS TXT model and LLDP system description
#   upnp         SSDP device and service URNs and the SERVER header
#   dhcp_class   DHCP vendor class
#   dhcp_params  DHCP parameter request list
#   vendor       the OUI vendor of the MAC address
#
# (field, pattern searched case-insensitively, kind, os, weight)
RULES: tuple[tuple[str, str, str | None, str | None, int], ...] = (
    ("lldp", r"\brouter\b", "router", None, STRONG),
    ("lldp", r"\bwlan-ap\b", "access_point", None, STRONG),
    ("lldp", r"\bbridge\b", "switch", None, STRONG),
    ("name", r"ipad", "tablet", "ipados", STRONG),
    ("name", r"iphone", "phone", "ios", STRONG),
    ("name", r"macbook|imac|mac-?mini|mac-?pro|mac-?studio", "computer", "macos", STRONG),
    ("name", r"apple-?tv", "media_player", "tvos", STRONG),
    ("name", r"apple-?watch", "wearable", None, STRONG),
    ("name", r"homepod", "speaker", None, STRONG),
    ("name", r"galaxy-?tab|^sm-[tx]\d", "tablet", "android", STRONG),
    ("name", r"galaxy-?watch|pixel-?watch", "wearable", None, STRONG),
    ("name", r"galaxy-?book", "computer", "windows", STRONG),
    ("name", r"galaxy|^sm-[afgmns]\d", "phone", "android", STRONG),
    ("name", r"pixelbook", "computer", "chromeos", STRONG),
    ("name", r"pixel-?tablet", "tablet", "android", STRONG),
    ("name", r"pixel|oneplus|redmi", "phone", "android", STRONG),
    ("name", r"kindle", "tablet", "fire_os", STRONG),
    ("name", r"fire-?tv|^aft[a-z]", "media_player", "fire_os", STRONG),
    ("name", r"^android[-_]", None, "android", STRONG),
    ("name", r"lgwebostv|webos", "tv", "webos", STRONG),
    ("name", r"bravia|^\[tv\]|samsung.*tv", "tv", None, STRONG),
    ("name", r"chromecast", "media_player", None, STRONG),
    ("name", r"roku", "media_player", None, STRONG),
    ("name", r"google-?home|google-?nest-?mini|nest-?(mini|audio)", "speaker", None, STRONG),
    ("name", r"sonos", "speaker", None, STRONG),
    ("name", r"xbox", "game_console", None, STRONG),
    ("name", r"playstation|^ps[345]-", "game_console", None, STRONG),
    ("name", r"nintendo", "game_console", None, STRONG),
    ("name", r"^(hp|npi)[0-9a-f]{6}\b|laserjet|officejet|deskjet", "printer", None, STRONG),
    ("name", r"^br[nw][0-9a-f]{12}$|^epson", "printer", None, STRONG),
    ("name", r"^(desktop|laptop)-[a-z0-9]{7,8}$", "computer", "windows", STRONG),
    ("name", r"^raspberrypi", "computer", "linux", STRONG),
    ("name", r"diskstation|rackstation|^qnap|^ts-\d{3}", "storage", None, STRONG),
    ("name", r"fritz-?repeater|^(uap|u6|u7)-", "access_point", None, STRONG),
    ("name", r"^usw-", "switch", None, STRONG),
    ("name", r"fritz-?box", "router", None, STRONG),
    ("name", r"^(esp|esp32|esp8266)[-_]|^tasmota|^shelly", "smart_home", None, STRONG),
    ("name", r"^wled|^philips-hue", "smart_home", None, STRONG),
    ("name", r"\bnas\b", "storage", None, WEAK),
    ("name", r"camera|ipcam|doorbell", "camera", None, WEAK),
    ("service", r"^(ipp|ipps|printer|pdl-datastream)$", "printer", None, STRONG),
    ("service", r"^(uscan|uscans|scanner)$", "printer", None, STRONG),
    ("service", r"^sonos$", "speaker", None, STRONG),
    ("service", r"^(hap|hue|esphomelib|miio|wled|shelly)$", "smart_home", None, STRONG),
    ("service", r"^(nanoleafapi|lutron)$", "smart_home", None, STRONG),
    ("service", r"^home-assistant$", "server", None, STRONG),
    ("service", r"^axis-video$", "camera", None, STRONG),
    ("service", r"^airport$", "access_point", None, STRONG),
    ("service", r"^amzn-wplay$", "media_player", "fire_os", STRONG),
    ("service", r"^androidtvremote2?$", "media_player", "android", WEAK),
    ("service", r"^(googlecast|mediaremotetv)$", "media_player", None, WEAK),
    ("service", r"^spotify-connect$", "speaker", None, WEAK),
    ("service", r"^(adisk|afpovertcp|smb|nfs)$", "storage", None, WEAK),
    ("service", r"^workstation$", "computer", "linux", WEAK),
    ("model", r"^ipad\d", "tablet", "ipados", STRONG),
    ("model", r"^iphone\d", "phone", "ios", STRONG),
    ("model", r"^(macbook|imac|macmini|macpro|mac\d+,)", "computer", "macos", STRONG),
    ("model", r"^appletv\d", "media_player", "tvos", STRONG),
    ("model", r"^audioaccessory\d", "speaker", None, STRONG),
    ("model", r"^watch\d", "wearable", None, STRONG),
    ("model", r"chromecast|shield android tv", "media_player", None, STRONG),
    ("model", r"google home|nest (mini|audio)", "speaker", None, STRONG),
    ("model", r"laserjet|officejet|deskjet|pixma|ecotank|workforce", "printer", None, STRONG),
    ("model", r"imageclass|^(mfc|hl|dcp)-", "printer", None, STRONG),
    ("model", r"sonos", "speaker", None, STRONG),
    ("model", r"bravia", "tv", None, STRONG),
    ("model", r"diskstation|rackstation|^ds\d{3,4}|^ts-\d{3}", "storage", None, STRONG),
    ("model", r"access point|^u[67]-|^uap", "access_point", None, WEAK),
    ("model", r"\bswitch\b", "switch", None, WEAK),
    ("model", r"\brouter\b|\bgateway\b", "router", None, WEAK),
    ("upnp", r"device:(internetgatewaydevice|wan(connection)?device)", "router", None, STRONG),
    ("upnp", r"fritz!box", "router", None, STRONG),
    ("upnp", r"device:wlanaccesspointdevice", "access_point", None, STRONG),
    ("upnp", r"device:printer", "printer", None, STRONG),
    ("upnp", r"device:zoneplayer|sonos|heos|denon", "speaker", None, STRONG),
    ("upnp", r"webos", "tv", "webos", STRONG),
    ("upnp", r"tizen", "tv", "tizen", STRONG),
    ("upnp", r"urn:samsung\.com:device:remotecontrolreceiver", "tv", None, STRONG),
    ("upnp", r"urn:schemas-sony-com:service:(scalarwebapi|ircc)", "tv", None, STRONG),
    ("upnp", r"roku", "media_player", None, STRONG),
    ("upnp", r"urn:belkin:device", "smart_home", None, STRONG),
    ("upnp", r"synology|qnap", "storage", None, STRONG),
    ("upnp", r"microsoft-windows|windows nt", "computer", "windows", STRONG),
    ("upnp", r"device:mediarenderer", "media_player", None, WEAK),
    ("upnp", r"device:mediaserver", "storage", None, WEAK),
    ("upnp", r"\bandroid\b", None, "android", WEAK),
    ("upnp", r"\blinux\b", None, "linux", WEAK),
    ("dhcp_class", r"xbox", "game_console", None, STRONG),
    ("dhcp_class", r"^msft 5\.0", "computer", "windows", STRONG),
    ("dhcp_class", r"^android-dhcp-|:android", None, "android", STRONG),
    ("dhcp_class", r"jetdirect", "printer", None, STRONG),
    ("dhcp_class", r"^dhcpcd-[^:]*:linux|^udhcp", None, "linux", WEAK),
    ("dhcp_params", r"^1,3,6,15,31,33,43,44,46,47,119,121,249,252$", "computer", "windows", STRONG),
    ("vendor", r"raspberry pi", "computer", "linux", STRONG),
    ("vendor", r"sonos", "speaker", None, STRONG),
    ("vendor", r"nintendo|sony interactive", "game_console", None, STRONG),
    ("vendor", r"roku", "media_player", None, STRONG),
    ("vendor", r"vizio", "tv", None, STRONG),
    ("vendor", r"brother industries|seiko epson|lexmark|xerox", "printer", None, STRONG),
    ("vendor", r"kyocera|ricoh|konica", "printer", None, STRONG),
    ("vendor", r"synology|qnap", "storage", None, STRONG),
    ("vendor", r"hikvision|dahua|axis communications|reolink", "camera", None, STRONG),
    ("vendor", r"ring llc|\barlo\b", "camera", None, STRONG),
    (
        "vendor",
        r"espressif|tuya|shelly|allterco|signify|philips lighting",
        "smart_home",
        None,
        STRONG,
    ),
    (
        "vendor",
        r"\blifx\b|nest labs|ecobee|netatmo|\btado\b|ikea of sweden",
        "smart_home",
        None,
        STRONG,
    ),
    ("vendor", r"hewlett packard|hp inc|canon", "printer", None, WEAK),
    ("vendor", r"lg electronics|hisense|\btcl\b", "tv", None, WEAK),
    ("vendor", r"ubiquiti", "access_point", None, WEAK),
    ("vendor", r"avm audiovisuelles|tp-link|netgear|d-link|zyxel|linksys", "router", None, WEAK),
    (
        "vendor",
        r"arcadyan|sagemcom|technicolor|mikrotik|routerboard|\beero\b",
        "router",
        None,
        WEAK,
    ),
    ("vendor", r"intel corporate|\bdell\b|lenovo|micro-star|asrock", "computer", None, WEAK),
    ("vendor", r"western digital", "storage", None, WEAK),
)

_COMPILED = tuple(
    (field, re.compile(pattern, re.IGNORECASE), kind, os, weight)
    for field, pattern, kind, os, weight in RULES
)


def classify(evidence: dict[str, list[str]]) -> tuple[str, str | None]:
    """(kind, os) for a host; kind "unknown" and os None when unsure."""
    kinds: Counter[str] = Counter()
    systems: Counter[str] = Counter()
    for field, values in evidence.items():
        rules = [rule for rule in _COMPILED if rule[0] == field.partition(".")[0]]
        for value in values:
            for _, pattern, kind, os, weight in rules:
                if pattern.search(value):
                    kinds.update({kind: weight} if kind else {})
                    systems.update({os: weight} if os else {})
                    break
    return _decide(kinds) or "unknown", _decide(systems)


def _decide(votes: Counter[str]) -> str | None:
    ranked = votes.most_common(2)
    if not ranked or ranked[0][1] < STRONG:
        return None
    if len(ranked) == 2 and ranked[1][1] == ranked[0][1]:
        return None
    return ranked[0][0]
