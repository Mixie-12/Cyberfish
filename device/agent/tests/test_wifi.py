"""Wi-Fi watch (ADR 0019).

The files in data/ are real `iw ... scan` output posted in public forums and
mailing lists; only long HE capability sections were cut. They cover iw's
current format (fractional frequencies, 6 GHz, WPA3), older formats, and
indentation as tabs, eight spaces, four spaces and one space.
"""

import logging
import os
from pathlib import Path

import pytest

from dekoi_agent import wifi
from dekoi_agent.wifi import AccessPoint, Watch, decode_ssid, parse_scan

DATA = Path(__file__).parent / "data"
START = 1_800_000_000.0
DAY = 24 * 60 * 60
HOME = "Kari's Wi-Fi"
SETTINGS = {"name": HOME, "country": "NO"}


def sample(name: str) -> str:
    return (DATA / name).read_text()


def test_current_iw_output_with_wpa2_and_wpa3_on_6_ghz() -> None:
    assert parse_scan(sample("iw-wpa2-wpa3-6ghz.txt")) == [
        AccessPoint("58:1d:d8:40:82:e8", "Livebox-8A12", "wpa2", 6, -42),
        AccessPoint("58:1d:d8:40:82:e0", "Livebox-8A12", "wpa3", 37, -51),
    ]


def test_open_networks_wpa_version_1_and_a_hidden_name() -> None:
    assert parse_scan(sample("iw-open-wpa-hidden.txt")) == [
        AccessPoint("00:a0:f8:42:1e:f2", "Edc9sse1", "wpa", 6, -48),
        AccessPoint("02:1a:11:ff:5f:67", "AnthonyAP", "wpa2", 6, -38),
        AccessPoint("00:21:d8:33:8e:10", "attwifi", "open", 1, -46),
        AccessPoint("84:78:ac:e8:ab:83", "CableWiFi", "open", 1, -61),
        AccessPoint("00:21:d8:33:8e:11", "", "open", 1, -50),
        AccessPoint("ec:e1:a9:31:ec:c1", "xfinitywifi", "open", 11, -73),
        AccessPoint("00:26:98:ec:a4:03", "CableWiFi", "open", 11, -72),
    ]


def test_wep_is_privacy_without_wpa_or_rsn() -> None:
    assert parse_scan(sample("iw-wep.txt")) == [
        AccessPoint("f8:1a:67:56:42:96", "scoobly", "wep", 4, -75),
    ]


def test_an_access_point_is_as_weak_as_the_weakest_security_it_accepts() -> None:
    assert parse_scan(sample("iw-transition-and-mixed.txt")) == [
        AccessPoint("06:ec:da:fd:81:02", "SSID1", "wpa2", 6, -52),
        AccessPoint("78:2c:29:8a:a5:be", "yangyangyang", "wpa", 1, -63),
    ]


def test_a_scan_cut_at_the_size_limit_loses_its_last_access_point() -> None:
    text = sample("iw-wpa2-wpa3-6ghz.txt")
    # Cut before the second block's RSN element: it would look like WEP.
    cut = text[: text.index("Authentication suites: SAE")]
    assert [ap.bssid for ap in parse_scan(cut, complete=False)] == ["58:1d:d8:40:82:e8"]


def test_lines_forged_in_a_wps_field_cannot_make_an_open_network_secure() -> None:
    # iw prints WPS strings as raw bytes, so a beacon can add lines. The
    # privacy bit comes from the kernel, before any element is printed.
    text = (
        sample("iw-wep.txt")
        .replace("ESS Privacy ShortPreamble", "ESS ShortPreamble")
        .replace(
            "Device name: Wireless Router TL-WR1043ND",
            "Device name: x\n\tRSN:\t * Version: 1\n\t\t * Authentication suites: SAE",
        )
    )
    (ap,) = parse_scan(text)
    assert ap.security == "open"


def test_only_newline_separates_lines_and_odd_blocks_are_skipped() -> None:
    block = block_for("02:00:00:00:00:01", "Home\x1cBSS 02:00:00:00:00:02", "wpa2")
    text = "garbage before the first block\nBSS 68:X:X:X:X:c3(on wlan0)\n\tSSID: masked\n" + block
    assert [ap.bssid for ap in parse_scan(text)] == ["02:00:00:00:00:01"]


def test_a_block_without_name_or_capability_is_ignored() -> None:
    text = "BSS 02:00:00:00:00:01(on wlan0)\n\tfreq: 2412\n\tSSID: Home\n"
    assert parse_scan(text) == []


@pytest.mark.parametrize(
    ("printed", "name"),
    [
        ("Kari's Wi-Fi", "Kari's Wi-Fi"),
        ("\\x20Home\\x20", "Home"),
        ("Home\\x00", "Home"),
        ("\\xe2\\x80\\x8bHome", "Home"),
        ("Caf\\xc3\\xa9", "Café"),
        ("back\\x5cslash", "back\\slash"),
        ("\\xff\\xfe", "��"),
        ("A" * 40, "A" * 32),
        ("Home\\x0a\\x1b[31m", "Home[31m"),
    ],
)
def test_names_are_decoded_cleaned_and_bounded(printed: str, name: str) -> None:
    assert decode_ssid(printed) == name


def test_frequencies_map_to_channels_and_odd_signals_are_dropped() -> None:
    text = "".join(
        block_for(f"02:00:00:00:00:{index:02x}", "n", "wpa2", freq, signal)
        for index, (freq, signal) in enumerate(
            [
                ("2484", "-45.00 dBm"),
                ("5180", "-45.-50 dBm"),
                ("5935.0", "60/100"),
                ("4920", "-130.00 dBm"),
            ]
        )
    )
    assert [(ap.channel, ap.signal_dbm) for ap in parse_scan(text)] == [
        (14, -45),
        (36, -45),
        (2, None),
        (None, None),
    ]


def block_for(
    bssid: str, ssid: str, security: str, freq: str = "2437", signal: str = "-40.00 dBm"
) -> str:
    """One access point in iw's current format, as in iw-wpa2-wpa3-6ghz.txt."""
    privacy = "" if security == "open" else " Privacy"
    suites = {"wpa2": "PSK", "wpa3": "SAE"}
    rsn = (
        f"\tRSN:\t * Version: 1\n\t\t * Authentication suites: {suites[security]}\n"
        if security in suites
        else ""
    )
    return (
        f"BSS {bssid}(on wlan0)\n\tfreq: {freq}\n"
        f"\tcapability: ESS{privacy} ShortSlotTime (0x0411)\n\tsignal: {signal}\n"
        f"\tSSID: {ssid}\n{rsn}"
    )


class Host:
    """The host side: the shared directories and the scan it writes."""

    def __init__(self, root: Path) -> None:
        self.scan = root / "wifi" / "scan"
        self.country = root / "wifi-country" / "country"
        self.scan.parent.mkdir()
        self.country.parent.mkdir()

    def write(self, at: float, status: str, *blocks: str) -> None:
        self.scan.write_text(status + "\n" + "".join(blocks))
        os.utime(self.scan, (at, at))


@pytest.fixture
def host(tmp_path: Path) -> Host:
    return Host(tmp_path)


def watch_for(host: Host, tmp_path: Path) -> Watch:
    return Watch(tmp_path / "wifi.json", host.scan, host.country)


def learned_home(host: Host, tmp_path: Path) -> Watch:
    """A watch that learned one WPA2 access point and finished learning."""
    watch = watch_for(host, tmp_path)
    host.write(START, "scan", block_for("02:00:00:00:00:01", HOME, "wpa2"))
    assert watch.check(START, SETTINGS) == []
    assert watch.status == "learning"
    host.write(START + DAY, "scan", block_for("02:00:00:00:00:01", HOME, "wpa2"))
    assert watch.check(START + DAY, SETTINGS) == []
    assert watch.status == "watching"
    return watch


def test_without_a_home_name_the_radio_is_not_asked_for(host: Host, tmp_path: Path) -> None:
    host.country.write_text("NO\n")
    watch = watch_for(host, tmp_path)
    assert watch.check(START, {"name": None, "country": "NO"}) == []
    assert watch.status == "no_name"
    assert not host.country.exists()


def test_without_a_country_the_radio_stays_off(host: Host, tmp_path: Path) -> None:
    host.country.write_text("NO\n")
    watch = watch_for(host, tmp_path)
    assert watch.check(START, {"name": HOME, "country": None}) == []
    assert watch.status == "no_country"
    assert not host.country.exists()


@pytest.mark.parametrize("reply", [None, [], {"name": 5}, {"name": "\x00 "}, {"name": "x" * 33}])
def test_replies_without_a_usable_name_turn_the_watch_off(
    host: Host, tmp_path: Path, reply: object
) -> None:
    watch = watch_for(host, tmp_path)
    assert watch.check(START, reply) == []
    assert watch.status == "no_name"


@pytest.mark.parametrize("country", ["no", "NOR", "N0", "NO\n", 47])
def test_only_two_capital_letters_are_a_country(
    host: Host, tmp_path: Path, country: object
) -> None:
    watch = watch_for(host, tmp_path)
    watch.check(START, {"name": HOME, "country": country})
    assert watch.status == "no_country"
    assert not host.country.exists()


def test_a_card_from_before_the_watch_has_no_radio(tmp_path: Path) -> None:
    watch = Watch(tmp_path / "wifi.json", tmp_path / "wifi" / "scan", tmp_path / "c" / "country")
    assert watch.check(START, SETTINGS) == []
    assert watch.status == "no_radio"


def test_the_country_asks_the_host_to_scan_and_a_silent_host_means_no_radio(
    host: Host, tmp_path: Path
) -> None:
    watch = watch_for(host, tmp_path)
    watch.check(START, SETTINGS)
    assert host.country.read_text() == "NO\n"
    assert oct(host.country.stat().st_mode & 0o777) == oct(0o644)
    os.utime(host.country, (START, START))
    watch.check(START + 60, SETTINGS)
    assert watch.status == "learning", "the host has a timer period to start"
    watch.check(START + 16 * 60, SETTINGS)
    assert watch.status == "no_radio"


@pytest.mark.parametrize("status", ["uplink", "no_radio"])
def test_the_host_says_why_it_does_not_scan(host: Host, tmp_path: Path, status: str) -> None:
    host.write(START, status)
    watch = watch_for(host, tmp_path)
    assert watch.check(START, SETTINGS) == []
    assert watch.status == status


def test_learning_reports_nothing_for_a_day(host: Host, tmp_path: Path) -> None:
    watch = watch_for(host, tmp_path)
    host.write(START, "scan", block_for("02:00:00:00:00:01", HOME, "wpa2"))
    watch.check(START, SETTINGS)
    host.write(START + DAY - 60, "scan", block_for("02:00:00:00:00:99", HOME, "open"))
    assert watch.check(START + DAY - 60, SETTINGS) == []
    assert watch.status == "learning"


def test_an_open_copy_of_the_home_network_is_an_evil_twin(host: Host, tmp_path: Path) -> None:
    watch = learned_home(host, tmp_path)
    at = START + DAY + 300
    host.write(
        at,
        "scan",
        block_for("02:00:00:00:00:01", HOME, "wpa2"),
        block_for("02:00:00:00:00:66", HOME, "open", "5180", "-38.00 dBm"),
        block_for("02:00:00:00:00:77", "Neighbour", "open"),
    )
    assert watch.check(at, SETTINGS) == [
        {
            "occurred_at": "2027-01-16T08:05:00+00:00",
            "kind": "evil_twin",
            "src_mac": "02:00:00:00:00:66",
            "detail": {
                "security": "open",
                "home_security": "wpa2",
                "channel": 36,
                "signal_dbm": -38,
            },
        }
    ]
    assert watch.check(at + 300, SETTINGS) == [], "reported once a day, not every scan"


def test_a_copy_with_the_same_bssid_but_weaker_security_is_an_evil_twin(
    host: Host, tmp_path: Path
) -> None:
    watch = learned_home(host, tmp_path)
    host.write(START + DAY, "scan", block_for("02:00:00:00:00:01", HOME, "open"))
    (event,) = watch.check(START + DAY, SETTINGS)
    assert event["kind"] == "evil_twin"


def test_a_new_access_point_with_the_same_security_is_reported_once(
    host: Host, tmp_path: Path
) -> None:
    watch = learned_home(host, tmp_path)
    host.write(START + DAY, "scan", block_for("02:00:00:00:00:02", HOME, "wpa3"))
    (event,) = watch.check(START + DAY, SETTINGS)
    assert event["kind"] == "new_access_point"
    assert event["detail"]["security"] == "wpa3"
    later = START + 3 * DAY
    host.write(later, "scan", block_for("02:00:00:00:00:02", HOME, "wpa3"))
    assert watch.check(later, SETTINGS) == [], "it is known now"


def test_an_evil_twin_that_stays_is_reported_again_after_a_day(host: Host, tmp_path: Path) -> None:
    watch = learned_home(host, tmp_path)
    twin = block_for("02:00:00:00:00:66", HOME, "wep")
    for at, expected in [(START + DAY, 1), (START + 1.5 * DAY, 0), (START + 2 * DAY, 1)]:
        host.write(at, "scan", twin)
        assert len(watch.check(at, SETTINGS)) == expected


def test_with_nothing_learned_the_home_network_counts_as_wpa2(host: Host, tmp_path: Path) -> None:
    watch = watch_for(host, tmp_path)
    host.write(START, "scan")
    watch.check(START, SETTINGS)
    host.write(
        START + DAY,
        "scan",
        block_for("02:00:00:00:00:01", HOME, "wpa2"),
        block_for("02:00:00:00:00:02", HOME, "open"),
    )
    kinds = [event["kind"] for event in watch.check(START + DAY, SETTINGS)]
    assert kinds == ["new_access_point", "evil_twin"]


def test_a_flood_of_copies_raises_a_bounded_number_of_alerts(host: Host, tmp_path: Path) -> None:
    watch = learned_home(host, tmp_path)
    flood = [block_for(f"02:00:00:00:01:{n:02x}", HOME, "open") for n in range(200)]
    host.write(START + DAY, "scan", *flood)
    assert len(watch.check(START + DAY, SETTINGS)) == wifi.MAX_ALERTS
    host.write(START + DAY + 300, "scan", *flood)
    assert watch.check(START + DAY + 300, SETTINGS) == []


def test_a_new_name_learns_again(host: Host, tmp_path: Path) -> None:
    watch = learned_home(host, tmp_path)
    renamed = {"name": "New name", "country": "NO"}
    host.write(START + DAY, "scan", block_for("02:00:00:00:00:66", "New name", "open"))
    assert watch.check(START + DAY, renamed) == []
    assert watch.status == "learning"


def test_what_was_learned_survives_a_restart(host: Host, tmp_path: Path) -> None:
    learned_home(host, tmp_path)
    assert oct((tmp_path / "wifi.json").stat().st_mode & 0o777) == oct(0o600)
    restarted = watch_for(host, tmp_path)
    assert restarted.status == "watching"
    host.write(START + DAY, "scan", block_for("02:00:00:00:00:01", HOME, "wpa2"))
    assert restarted.check(START + DAY, SETTINGS) == []


def test_a_damaged_state_file_starts_learning_again(
    host: Host, tmp_path: Path, caplog: pytest.LogCaptureFixture
) -> None:
    (tmp_path / "wifi.json").write_text("{not json")
    with caplog.at_level(logging.WARNING):
        watch = watch_for(host, tmp_path)
    assert watch.status == "no_name"
    assert "learning again" in caplog.text


def test_a_scan_from_a_future_host_format_is_ignored(host: Host, tmp_path: Path) -> None:
    host.write(START, "json", "{}")
    watch = watch_for(host, tmp_path)
    assert watch.check(START, SETTINGS) == []
    assert watch.status == "no_radio"
