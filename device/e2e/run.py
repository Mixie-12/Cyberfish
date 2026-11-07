"""End-to-end test of the device stack against a local Supabase.

Runs as root on a disposable Linux machine with systemd and rootful Podman
(the CI runner). The real Quadlet units start the real images, as on a Pi.
The test claims the device the way the app does, attacks the decoys from
another container, and then reads back what the app would show.

Not covered here, because it would take over the machine's firewall: the
nftables and ulogd2 path. Port scans are fed in as ulogd2 JSON lines, and the
real path is verified on hardware.

Usage: run.py SUPABASE_STATUS_JSON
"""

import base64
import hashlib
import hmac
import json
import secrets
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

ATTACKER_IMAGE = (
    "docker.io/library/alpine@sha256:"
    "3f4d6b32e26703c6082839234f43769d5046b3ac7c910033911d1e71d8cd630a"
)
CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
DECOYS = ["ftp", "http", "mysql", "rdp", "redis", "ssh", "telnet", "vnc"]
SCANNER_IP = "192.0.2.10"
SCAN_LOG = Path("/var/log/ulog/dekoi-scan.json")
TIMEOUT_SECONDS = 240


class Backend:
    def __init__(self, status: dict) -> None:
        self.url = status["API_URL"]
        self.publishable = status["PUBLISHABLE_KEY"]
        self.secret = status["SECRET_KEY"]
        self.jwt_secret = status["JWT_SECRET"].encode()

    def request(self, method: str, path: str, body: object, headers: dict) -> object:
        request = urllib.request.Request(  # noqa: S310 - local test stack
            self.url + path,
            data=None if body is None else json.dumps(body).encode(),
            method=method,
            headers={"Content-Type": "application/json", **headers},
        )
        with urllib.request.urlopen(request, timeout=10) as response:  # noqa: S310
            return json.loads(response.read() or b"null")

    def create_user(self) -> str:
        email = f"e2e-{secrets.token_hex(4)}@test.invalid"
        headers = {"apikey": self.secret, "Authorization": f"Bearer {self.secret}"}
        user = self.request(
            "POST", "/auth/v1/admin/users", {"email": email, "email_confirm": True}, headers
        )
        return user["id"]

    def user_token(self, user_id: str) -> str:
        """A session token as Supabase Auth would issue after Google sign-in."""

        def encode(data: bytes) -> str:
            return base64.urlsafe_b64encode(data).rstrip(b"=").decode()

        now = int(time.time())
        claims = {
            "sub": user_id,
            "role": "authenticated",
            "aud": "authenticated",
            "iat": now,
            "exp": now + 3600,
        }
        unsigned = (
            encode(b'{"alg":"HS256","typ":"JWT"}') + "." + encode(json.dumps(claims).encode())
        )
        signature = hmac.new(self.jwt_secret, unsigned.encode(), hashlib.sha256).digest()
        return unsigned + "." + encode(signature)

    def as_user(self, token: str, method: str, path: str, body: object = None) -> object:
        headers = {"apikey": self.publishable, "Authorization": f"Bearer {token}"}
        return self.request(method, path, body, headers)


def run(*command: str) -> str:
    return subprocess.run(command, check=True, capture_output=True, text=True).stdout


def wait_for(description: str, check) -> object:
    deadline = time.monotonic() + TIMEOUT_SECONDS
    while time.monotonic() < deadline:
        result = check()
        if result:
            print(f"ok: {description}")
            return result
        time.sleep(3)
    raise SystemExit(f"timed out waiting for: {description}")


def attack(password: str) -> None:
    script = (
        "apk add -q --no-progress openssh-client sshpass curl arp-scan >/dev/null"
        f" && (sshpass -p '{password}' ssh -o StrictHostKeyChecking=no"
        " -o UserKnownHostsFile=/dev/null -o PreferredAuthentications=password"
        " -o PubkeyAuthentication=no -o ConnectTimeout=10"
        " admin@host.containers.internal true || true)"
        f" && (curl -s -o /dev/null --max-time 10 -d 'username=admin&password={password}'"
        " http://host.containers.internal/index.html || true)"
        " && (timeout 20 arp-scan --localnet --interface=eth0 >/dev/null || true)"
    )
    run("podman", "run", "--rm", ATTACKER_IMAGE, "sh", "-c", script)


def feed_port_scan() -> None:
    now = int(time.time())
    lines = [
        json.dumps(
            {
                "oob.prefix": "dekoi-scan",
                "oob.time.sec": now,
                "src_ip": SCANNER_IP,
                "dest_port": port,
            }
        )
        for port in (25, 110, 139, 443, 445, 8080)
    ]
    with SCAN_LOG.open("a") as log:
        log.write("\n".join(lines) + "\n")


def main() -> None:
    backend = Backend(json.loads(Path(sys.argv[1]).read_text()))
    token = backend.user_token(backend.create_user())

    claim_code = "".join(secrets.choice(CROCKFORD) for _ in range(26))
    device_id = backend.as_user(
        token, "POST", "/rest/v1/rpc/claim_device_v1", {"claim_code": claim_code, "name": "E2E"}
    )
    print(f"ok: claimed device {device_id}")

    Path("/etc/dekoi").mkdir(exist_ok=True)
    Path("/etc/dekoi/claim-code").write_text(claim_code + "\n")
    SCAN_LOG.parent.mkdir(parents=True, exist_ok=True)
    SCAN_LOG.touch(mode=0o644)
    run("systemctl", "daemon-reload")
    run("systemctl", "start", "dekoi-canary.service", "dekoi-agent.service", "dekoi-ids.service")
    print("ok: units started (canary healthy, agent ready)")

    def ids_running() -> bool:
        journal = run("journalctl", "--no-pager", "-u", "dekoi-ids.service")
        active = subprocess.run(
            ["systemctl", "is-active", "--quiet", "dekoi-ids.service"], check=False
        )
        return active.returncode == 0 and "engine started" in journal

    wait_for("IDS loaded its rules and is capturing, locked down", ids_running)

    def device() -> dict:
        (row,) = backend.as_user(
            token, "GET", "/rest/v1/devices?select=registered_at,online,services"
        )
        return row

    wait_for(
        "device registered, online, all decoys listening",
        lambda: (
            (d := device())["registered_at"] and d["online"] and sorted(d["services"]) == DECOYS
        ),
    )

    password = "e2e-" + secrets.token_hex(8)
    attack(password)
    feed_port_scan()

    def events() -> list[dict]:
        return backend.as_user(token, "GET", "/rest/v1/events?select=*")

    def seen(kind: str, service: str | None = None) -> bool:
        return any(
            e["kind"] == kind and (service is None or e["service"] == service) for e in events()
        )

    wait_for("SSH login attempt", lambda: seen("login_attempt", "ssh"))
    wait_for("HTTP login attempt", lambda: seen("login_attempt", "http"))
    wait_for("port scan", lambda: seen("port_scan"))
    wait_for("network sweep seen in ARP traffic", lambda: seen("host_scan"))

    rows = events()
    logins = [e for e in rows if e["kind"] == "login_attempt"]
    assert all(e["detail"].get("username") == "admin" for e in logins), logins
    assert password not in json.dumps(rows), "a typed password reached the backend"
    (scan,) = [e for e in rows if e["kind"] == "port_scan"]
    assert scan["src_ip"] == SCANNER_IP and scan["detail"]["ports"] == [25, 110, 139, 443, 445], (
        scan
    )
    print("ok: events are correct and contain no passwords")

    wait_for(
        "network inventory from arp-scan",
        lambda: backend.as_user(token, "GET", "/rest/v1/network_hosts?select=mac"),
    )
    print("end-to-end test passed")


if __name__ == "__main__":
    main()
