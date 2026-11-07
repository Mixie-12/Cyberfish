import json
import socket
from pathlib import Path

from dekoi_agent.sources import (
    DatagramInbox,
    FileFollower,
    clean_text,
    flag_bait,
    parse_canary,
    parse_samba,
    parse_scan,
    parse_suricata,
)

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


# Lines as Samba 4.22.11 wrote them in the canary image (canary/persona/
# smb.conf) for smbclient -L -N, smbclient -U admin%... and smbclient -N get.
# The typed password appears nowhere in the log.
SAMBA_SIGN_IN = (
    '  {"timestamp": "2026-11-07T18:06:46.281521+0000", "type": "Authentication", '
    '"Authentication": {"version": {"major": 1, "minor": 3}, "eventId": 4625, "logonId": "0", '
    '"logonType": 3, "status": "NT_STATUS_NO_SUCH_USER", '
    '"localAddress": "ipv4:172.30.0.2:445", "remoteAddress": "ipv4:172.30.0.3:37230", '
    '"serviceDescription": "SMB2", "authDescription": null, "clientDomain": "WORKGROUP", '
    '"clientAccount": "admin", "workstation": "957BFF6FB34A", "becameAccount": null, '
    '"becameDomain": null, "becameSid": null, "mappedAccount": "admin", '
    '"mappedDomain": "WORKGROUP", "netlogonComputer": null, "netlogonTrustAccount": null, '
    '"netlogonNegotiateFlags": "0x00000000", "netlogonSecureChannelType": 0, '
    '"netlogonTrustAccountSid": null, "passwordType": "NTLMv2", '
    '"clientPolicyAccessCheck": null, "serverPolicyAccessCheck": null, "duration": 10020}}'
)
# smbclient -N first tries the local user name without any password.
SAMBA_ANONYMOUS = SAMBA_SIGN_IN.replace('"admin"', '"root"').replace("NTLMv2", "No-Password")
SAMBA_HEADER = "[2026/11/07 18:06:46.517369,  1] source3/modules/vfs_full_audit.c:637(do_log)"
SAMBA_SHARE_LIST = "  172.30.0.3|IPC_|/tmp|connect|ok|IPC$"
SAMBA_CONNECT = "  172.30.0.3|Public|/srv/decoy/Public|connect|ok|Public"
SAMBA_READ = (
    "  172.30.0.3|Public|/srv/decoy/Public|pread_recv|ok|/srv/decoy/Public/scans/passport.pdf"
)


def test_samba_sign_in_keeps_the_user_name() -> None:
    assert parse_samba(SAMBA_SIGN_IN, NOW) == {
        "occurred_at": "2026-11-07T18:06:46.281521+00:00",
        "kind": "login_attempt",
        "service": "smb",
        "src_ip": "172.30.0.3",
        "dst_port": 445,
        "detail": {"username": "admin"},
    }


def test_samba_share_connection_and_file_read() -> None:
    connection = parse_samba(SAMBA_CONNECT, NOW)
    assert connection == {
        "occurred_at": "2027-01-15T08:00:00+00:00",
        "kind": "connection",
        "service": "smb",
        "src_ip": "172.30.0.3",
        "dst_port": 445,
        "detail": {"share": "Public"},
    }
    read = parse_samba(SAMBA_READ, NOW)
    assert read is not None
    assert read["kind"] == "file_access"
    assert read["detail"] == {"share": "Public", "file": "scans/passport.pdf"}


def test_samba_browsing_is_not_an_event() -> None:
    for line in (SAMBA_ANONYMOUS, SAMBA_SHARE_LIST, SAMBA_HEADER, "", "  not|enough|fields"):
        assert parse_samba(line, NOW) is None, line
    failed = SAMBA_CONNECT.replace("|ok|", "|fail|")
    assert parse_samba(failed, NOW) is None


def test_samba_fields_are_bounded_and_cleaned() -> None:
    hostile = SAMBA_SIGN_IN.replace('"admin"', json.dumps("a\x1b[31m\u202e" + "x" * 300))
    event = parse_samba(hostile, NOW)
    assert event is not None
    assert event["detail"]["username"] == "a[31m" + "x" * 251
    file_name = SAMBA_READ.replace("passport.pdf", "pass|port\u200b.pdf")
    event = parse_samba(file_name, NOW)
    assert event is not None
    assert event["detail"]["file"] == "scans/pass|port.pdf"
    assert parse_samba(SAMBA_READ.replace("172.30.0.3", "not-an-ip"), NOW) is None
    ipv6 = SAMBA_SIGN_IN.replace("ipv4:172.30.0.3:37230", "ipv6:fe80::1%eth0:37230")
    event = parse_samba(ipv6, NOW)
    assert event is not None
    assert event["src_ip"] == "fe80::1"


BAIT_USERS = frozenset({"familie.admin", "nas-backup"})


def test_a_sign_in_with_a_bait_name_is_flagged_on_every_decoy() -> None:
    samba = parse_samba(SAMBA_SIGN_IN.replace('"admin"', '"FAMILIE.ADMIN"'), NOW)
    web = parse_canary(
        canary_line(3001, {"USERNAME": "nas-backup", "PASSWORD": "Backup#2023"}, dst_port=80),
        DECOYS,
        NOW,
    )
    for event in (samba, web):
        assert event is not None
        assert flag_bait(event, BAIT_USERS)["detail"]["bait"] is True
    assert "Backup#2023" not in json.dumps(web)


def test_only_sign_ins_with_a_bait_name_are_flagged() -> None:
    ordinary = parse_samba(SAMBA_SIGN_IN, NOW)
    read = parse_samba(SAMBA_READ.replace("scans/passport.pdf", "nas-backup"), NOW)
    connection = parse_canary(canary_line(3000, {"PATH": "/nas-backup"}, dst_port=80), DECOYS, NOW)
    for event in (ordinary, read, connection):
        assert event is not None
        assert "bait" not in flag_bait(event, BAIT_USERS)["detail"]


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


def suricata_line(severity: int = 2, src_ip: str = "192.168.1.66", **alert: object) -> str:
    record = {
        "timestamp": "2026-11-07T10:00:00.123456+0000",
        "event_type": "alert",
        "src_ip": src_ip,
        "src_port": 51234,
        "dest_ip": "192.168.1.20",
        "dest_port": 445,
        "payload_printable": "secret payload",
        "alert": {
            "signature_id": 2010937,
            "signature": "ET SCAN Suspicious inbound to mySQL port 3306",
            "category": "Potentially Bad Traffic",
            "severity": severity,
        }
        | alert,
    }
    return json.dumps(record)


def test_suricata_alert_keeps_rule_metadata_only() -> None:
    event = parse_suricata(suricata_line(), {"192.168.1.20"})
    assert event == {
        "occurred_at": "2026-11-07T10:00:00.123456+00:00",
        "kind": "ids_alert",
        "src_ip": "192.168.1.66",
        "dst_port": 445,
        "detail": {
            "severity": 2,
            "signature_id": 2010937,
            "signature": "ET SCAN Suspicious inbound to mySQL port 3306",
            "category": "Potentially Bad Traffic",
        },
    }
    assert "secret payload" not in json.dumps(event)


def test_suricata_low_severity_own_traffic_and_other_records_are_ignored() -> None:
    assert parse_suricata(suricata_line(severity=3), set()) is None
    assert parse_suricata(suricata_line(src_ip="192.168.1.20"), {"192.168.1.20"}) is None
    assert (
        parse_suricata(json.dumps({"event_type": "flow", "src_ip": "192.168.1.66"}), set()) is None
    )
    assert parse_suricata("not json", set()) is None


def test_datagram_inbox_receives_what_suricata_sends(tmp_path: Path) -> None:
    path = tmp_path / "eve.sock"
    inbox = DatagramInbox(path)
    assert inbox.read_lines() == []
    with socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM) as sender:
        sender.connect(str(path))
        sender.send(b'{"event_type": "alert"}')
        sender.send(b'{"event_type": "flow"}')
    assert inbox.read_lines() == ['{"event_type": "alert"}', '{"event_type": "flow"}']
    assert path.stat().st_mode & 0o777 == 0o622
