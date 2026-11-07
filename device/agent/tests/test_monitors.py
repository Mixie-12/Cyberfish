import json
import os
import re
from pathlib import Path

import pytest

from dekoi_agent import monitors
from dekoi_agent.monitors import STARTUP_SECONDS, Monitors, read_image

START = 1_800_000_000.0
SENSOR = "dekoi-ids 0.2.0 2026-11-02T04:31:07Z 2026-11-02"
MIGRATIONS = Path(__file__).parents[3] / "backend" / "supabase" / "migrations"


def stats(host: object = SENSOR, engines: object = None) -> str:
    engines = [{"id": 0, "rules_loaded": 45213, "rules_failed": 0}] if engines is None else engines
    record = {
        "timestamp": "2026-11-07T10:00:00.000000+0000",
        "event_type": "stats",
        "stats": {"uptime": 120, "detect": {"engines": engines}},
        "host": host,
    }
    return json.dumps(record)


def states(report: list[dict]) -> dict[str, str]:
    return {monitor["name"]: monitor["state"] for monitor in report}


def detail(report: list[dict], name: str) -> dict | None:
    (monitor,) = [m for m in report if m["name"] == name]
    return monitor.get("detail")


def test_monitors_start_then_hold_while_their_source_is_alive() -> None:
    health = Monitors(START)
    assert set(states(health.report(START)).values()) == {"starting"}
    assert [m["name"] for m in health.report(START)] == list(monitors.NAMES)

    health.alive("inventory", START + 1800, hosts=23)
    later = START + STARTUP_SECONDS
    report = health.report(later)
    assert states(report)["inventory"] == "ok"
    assert detail(report, "inventory") == {"hosts": 23}
    assert states(report)["arp_watch"] == "failed", "silent past the startup time"

    report = health.report(START + 1801)
    assert states(report)["inventory"] == "failed"
    assert detail(report, "inventory") == {"hosts": 23}, "the last count stays"


def test_a_monitor_that_fails_early_is_not_starting() -> None:
    health = Monitors(START)
    health.alive("name_poisoning", START + 10)
    assert states(health.report(START + 20))["name_poisoning"] == "failed"


def test_decoys_are_ok_only_when_all_listen() -> None:
    health = Monitors(START)
    health.decoys(START, 6, 8)
    report = health.report(START)
    assert states(report)["decoys"] == "starting"
    assert detail(report, "decoys") == {"decoys": 6}

    health.decoys(START + 60, 8, 8)
    assert states(health.report(START + 60))["decoys"] == "ok"

    at = START + STARTUP_SECONDS + 60
    health.decoys(at, 7, 8)
    report = health.report(at)
    assert states(report)["decoys"] == "failed"
    assert detail(report, "decoys") == {"decoys": 7}


def test_ids_statistics_give_rules_ruleset_and_image() -> None:
    health = Monitors(START)
    health.ids(START + 5, [stats()])
    report = health.report(START + 5 + monitors.IDS_SECONDS)
    assert states(report)["ids"] == "ok"
    assert detail(report, "ids") == {"rules": 45213, "ruleset_date": "2026-11-02"}
    assert health.ids_image == {"version": "0.2.0", "updated_at": "2026-11-02T04:31:07Z"}
    assert states(health.report(START + 6 + monitors.IDS_SECONDS))["ids"] == "failed"


def test_any_ids_record_shows_life() -> None:
    health = Monitors(START)
    health.ids(START, ['{"event_type": "alert", "alert": {}}', "not json", "[1, 2]"])
    report = health.report(START)
    assert states(report)["ids"] == "ok"
    assert detail(report, "ids") is None

    health = Monitors(START)
    health.ids(START, ["not json", "[1, 2]", '"text"'])
    assert states(health.report(START))["ids"] == "starting"


@pytest.mark.parametrize(
    "host",
    [
        None,
        42,
        "suricata",
        "dekoi-canary 0.2.0 2026-11-02T04:31:07Z 2026-11-02",
        "dekoi-ids 0.2.0 2026-11-02T04:31:07Z",
        "dekoi-ids 0.2.0 2026-11-02T04:31:07Z 2026-02-30",
        "dekoi-ids 0.2.0 2026-11-02T04:31:07Z 2026-11-2",
        "dekoi-ids 0.2.0 2026-11-02T04:31:07+01:00 2026-11-02",
        "dekoi-ids 0.2;0 2026-11-02T04:31:07Z 2026-11-02",
        "dekoi-ids " + "1" * 33 + " 2026-11-02T04:31:07Z 2026-11-02",
        "dekoi-ids 0.2.0  2026-11-02T04:31:07Z 2026-11-02",
    ],
)
def test_a_malformed_sensor_name_reports_no_image_or_ruleset(host: object) -> None:
    health = Monitors(START)
    health.ids(START, [stats(SENSOR)])
    health.ids(START + 60, [stats(host)])
    assert detail(health.report(START + 60), "ids") == {"rules": 45213}
    assert health.ids_image is None


@pytest.mark.parametrize(
    "engines",
    [
        [],
        "45213",
        [{"id": 0}],
        [{"rules_loaded": "45213"}],
        [{"rules_loaded": True}],
        [{"rules_loaded": -1}],
        [{"rules_loaded": 10_000_000}],
        [{"rules_loaded": 1}, "engine"],
    ],
)
def test_a_malformed_rule_count_is_left_out(engines: object) -> None:
    health = Monitors(START)
    health.ids(START, [stats(engines=engines)])
    assert detail(health.report(START), "ids") == {"ruleset_date": "2026-11-02"}


def test_rule_counts_of_several_engines_are_added() -> None:
    health = Monitors(START)
    health.ids(START, [stats(engines=[{"rules_loaded": 2}, {"rules_loaded": 3}])])
    assert detail(health.report(START), "ids")["rules"] == 5


def test_image_files_are_read_strictly(tmp_path: Path) -> None:
    image = tmp_path / "image"
    image.write_text("0.2.0 2026-11-02T04:31:07Z\n")
    assert read_image(image) == {"version": "0.2.0", "updated_at": "2026-11-02T04:31:07Z"}

    for text in ["", "0.2.0", "0.2.0 2026-11-02", "0.2.0 2026-11-02T04:31:07Z extra", "é 1"]:
        image.write_text(text)
        assert read_image(image) is None, text
    image.write_text("0.2.0 2026-11-02T04:31:07Z" + " " * 300 + "x")
    assert read_image(image) == {"version": "0.2.0", "updated_at": "2026-11-02T04:31:07Z"}, (
        "only the first bytes are read"
    )
    assert read_image(tmp_path / "missing") is None


def test_a_link_or_pipe_planted_by_the_decoys_is_not_read(tmp_path: Path) -> None:
    secret = tmp_path / "state"
    secret.write_text("0.2.0 2026-11-02T04:31:07Z\n")
    link = tmp_path / "image"
    link.symlink_to(secret)
    assert read_image(link) is None

    pipe = tmp_path / "pipe"
    os.mkfifo(pipe)
    assert read_image(pipe) is None, "returns at once instead of waiting for a writer"


def test_images_are_reported_in_a_fixed_order(tmp_path: Path) -> None:
    (tmp_path / "canary").write_text("0.2.0 2026-11-02T04:30:00Z\n")
    (tmp_path / "agent").write_text("0.2.0 2026-11-02T04:32:00Z\n")
    health = Monitors(START)
    assert health.images(tmp_path / "canary", tmp_path / "none") == [
        {"name": "canary", "version": "0.2.0", "updated_at": "2026-11-02T04:30:00Z"}
    ]
    health.ids(START, [stats()])
    assert [image["name"] for image in health.images(tmp_path / "canary", tmp_path / "agent")] == [
        "canary",
        "agent",
        "ids",
    ]


def test_the_backend_accepts_every_monitor_name() -> None:
    (migration,) = MIGRATIONS.glob("*_incidents_rooms_monitoring.sql")
    function = migration.read_text().split("create function private.valid_monitors")[1]
    vocabulary = re.search(r"m\.monitor ->> 'name'\) not in \(([^)]*)\)", function)
    assert vocabulary is not None
    assert set(re.findall(r"'([a-z_]+)'", vocabulary.group(1))) == set(monitors.NAMES)
