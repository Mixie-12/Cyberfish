import json
from pathlib import Path

from dekoi_agent.sources import FileFollower, clean_text, parse_canary, parse_scan

DECOYS = {22: "ssh", 80: "http"}
NOW = 1_800_000_000.0


def canary_line(logtype: int, logdata: dict, **fields: object) -> str:
    record = {
        "dst_host": "192.168.1.20",
        "dst_port": 22,
        "logtype": logtype,
        "logdata": logdata,
        "src_host": "192.168.1.50",
        "src_port": 51234,
        "utc_time": "2026-11-07 10:00:00.123456",
    }
    return json.dumps(record | fields)


def test_ssh_login_attempt_keeps_username_and_drops_password() -> None:
    line = canary_line(
        4002,
        {"USERNAME": "admin", "PASSWORD": "hunter2", "REMOTEVERSION": "SSH-2.0-OpenSSH_9.6"},
    )
    event = parse_canary(line, DECOYS, NOW)
    assert event == {
        "occurred_at": "2026-11-07T10:00:00.123456+00:00",
        "kind": "login_attempt",
        "service": "ssh",
        "src_ip": "192.168.1.50",
        "dst_port": 22,
        "detail": {"username": "admin", "client_version": "SSH-2.0-OpenSSH_9.6"},
    }
    assert "hunter2" not in json.dumps(event)


def test_redis_keeps_command_name_but_not_arguments() -> None:
    line = canary_line(17001, {"CMD": "AUTH", "ARGS": "secret-password"}, dst_port=6379)
    event = parse_canary(line, {6379: "redis"}, NOW)
    assert event is not None
    assert event["detail"] == {"command": "AUTH"}


def test_service_comes_from_the_decoy_port() -> None:
    event = parse_canary(canary_line(3000, {"PATH": "/"}, dst_port=80), DECOYS, NOW)
    assert event is not None
    assert event["service"] == "http"
    assert event["kind"] == "connection"


def test_ignored_and_malformed_lines() -> None:
    assert parse_canary(canary_line(1001, {}), DECOYS, NOW) is None
    assert parse_canary(canary_line(4002, {}, src_host="not-an-ip"), DECOYS, NOW) is None
    assert parse_canary("not json", DECOYS, NOW) is None
    assert parse_canary("[1, 2]", DECOYS, NOW) is None


def test_unparseable_time_falls_back_to_now() -> None:
    event = parse_canary(canary_line(4000, {}, utc_time="garbage"), DECOYS, NOW)
    assert event is not None
    assert event["occurred_at"] == "2027-01-15T08:00:00+00:00"


def test_clean_text_removes_control_and_formatting_characters() -> None:
    assert clean_text("ad\x1b[31mmin\u202e\u200b\x00") == "ad[31mmin"
    assert clean_text("x" * 300) == "x" * 256
    assert clean_text("  \n ") == ""


def test_parse_scan() -> None:
    record = {
        "oob.prefix": "dekoi-scan",
        "oob.time.sec": 1_800_000_000,
        "src_ip": "192.168.1.66",
        "dest_port": 445,
        "mac.saddr.str": "3c:22:fb:00:00:01",
    }
    assert parse_scan(json.dumps(record)) == (NOW, "192.168.1.66", "3c:22:fb:00:00:01", 445)
    assert parse_scan(json.dumps(record | {"oob.prefix": "other"})) is None
    assert parse_scan(json.dumps(record | {"dest_port": 70000})) is None


def test_follower_starts_at_end_and_reads_only_complete_lines(tmp_path: Path) -> None:
    log = tmp_path / "canary.log"
    log.write_text("old\n")
    follower = FileFollower(log, None)
    assert follower.read_lines() == []
    with log.open("a") as handle:
        handle.write("one\ntw")
    assert follower.read_lines() == ["one"]
    with log.open("a") as handle:
        handle.write("o\n")
    assert follower.read_lines() == ["two"]


def test_follower_handles_rotation_and_truncation(tmp_path: Path) -> None:
    log = tmp_path / "canary.log"
    log.write_text("first\n")
    follower = FileFollower(log, (log.stat().st_ino, 0))
    assert follower.read_lines() == ["first"]

    log.rename(tmp_path / "canary.log.1")
    log.write_text("after rotation\n")
    assert follower.read_lines() == ["after rotation"]

    log.write_text("short\n")
    assert follower.read_lines() == ["short"]


def test_follower_waits_for_a_missing_file(tmp_path: Path) -> None:
    follower = FileFollower(tmp_path / "missing.log", None)
    assert follower.read_lines() == []
