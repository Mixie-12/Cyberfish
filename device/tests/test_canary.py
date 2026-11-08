"""The canary image's own code: the persona renderer and the daemon supervisor."""

import importlib.util
import json
import re
import signal
import subprocess
import sys
import time
import zipfile
from datetime import UTC, datetime
from pathlib import Path
from types import ModuleType
from xml.etree import ElementTree

import pytest

CANARY = Path(__file__).parents[1] / "canary"
PERSONA = CANARY / "persona"
BAIT = PERSONA / "public"
# What the Containerfile renders, from canary/.
CONFIGURATION = ["opencanary.conf", "persona/smb.conf", "persona/avahi-daemon.conf", "persona/skin"]
# Card numbers that payment processors document for testing; no card has them.
TEST_CARDS = {"4111111111111111", "5555555555554444"}
# Weights of the mod 11 check digit of Norwegian account and organisation numbers.
ACCOUNT_WEIGHTS = [5, 4, 3, 2, 7, 6, 5, 4, 3, 2]
ORG_WEIGHTS = [3, 2, 7, 6, 5, 4, 3, 2]


def load(name: str) -> ModuleType:
    spec = importlib.util.spec_from_file_location(name, CANARY / f"{name}.py")
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def render(tmp_path: Path) -> Path:
    command = [sys.executable, "-I", "render.py", "persona/persona.json", str(tmp_path)]
    subprocess.run([*command, *CONFIGURATION, "persona/public"], cwd=CANARY, check=True)
    return tmp_path


def test_persona_fills_every_placeholder(tmp_path: Path) -> None:
    persona = json.loads((PERSONA / "persona.json").read_text(encoding="utf-8"))
    out = render(tmp_path)
    for path in (p for p in out.rglob("*") if p.is_file() and "public" not in p.parts):
        assert "$" not in path.read_text(encoding="utf-8"), path
    smb = (out / "persona" / "smb.conf").read_text(encoding="utf-8")
    assert f"netbios name = {persona['name']}" in smb
    assert f"workgroup = {persona['workgroup']}" in smb
    assert f"server string = {persona['description']}" in smb
    avahi = (out / "persona" / "avahi-daemon.conf").read_text(encoding="utf-8")
    assert f"host-name={persona['name']}" in avahi
    page = (out / "persona" / "skin" / "index.html").read_text(encoding="utf-8")
    assert f"<title>{persona['company']} - " in page
    for value in ("name", "workgroup", "sap_system"):
        assert persona[value] in page, value
    canary = json.loads((out / "opencanary.conf").read_text(encoding="utf-8"))
    assert f"[{persona['sap_system']}/100]" in canary["tcpbanner_4.datareceivedbanner"]


def test_login_page_posts_what_opencanary_reads(tmp_path: Path) -> None:
    page = (render(tmp_path) / "persona" / "skin" / "index.html").read_text(encoding="utf-8")
    assert '<form method="post" action="/index.html">' in page
    assert 'name="username"' in page
    assert 'name="password"' in page
    # OpenCanary shows the part between these markers only after a failed sign-in.
    assert page.count("<!--STARTERR-->") == page.count("<!--ENDERR-->") == 1
    # No external assets: nothing the page loads leaves the device.
    assert "http://" not in page
    assert "https://" not in page
    assert "<script" not in page


def test_web_banners_say_how_long_their_page_is(tmp_path: Path) -> None:
    canary = json.loads((render(tmp_path) / "opencanary.conf").read_text(encoding="utf-8"))
    banners = [v for k, v in canary.items() if k.endswith(".datareceivedbanner")]
    responses = [banner for banner in banners if banner.startswith("HTTP/1.1 ")]
    assert responses
    for response in responses:
        head, body = response.split("\r\n\r\n", 1)
        length = re.search(r"^content-length: (\d+)\r?$", head, re.MULTILINE | re.IGNORECASE)
        assert length is not None and int(length[1]) == len(body.encode()), head


def test_bait_files_are_small_and_open_in_the_format_their_name_says(tmp_path: Path) -> None:
    formats = load("render").FORMATS
    public = render(tmp_path) / "persona" / "public"
    sources = sorted(p.relative_to(BAIT) for p in BAIT.rglob("*") if p.is_file())
    made = sorted(p.relative_to(public) for p in public.rglob("*") if p.is_file())
    assert made == sorted(
        p.with_suffix("") if Path(p.stem).suffix in formats else p for p in sources
    )
    for path in made:
        content = (public / path).read_bytes()
        assert len(content) < 4096, path
        if path.suffix == ".pdf":
            xref = int(content.rsplit(b"startxref\n", 1)[1].split()[0])
            assert content.startswith(b"%PDF-1.4\n")
            assert content[xref:].startswith(b"xref\n0 7\n"), path
        elif path.suffix in (".xlsx", ".docx"):
            with zipfile.ZipFile(public / path) as package:
                assert "[Content_Types].xml" in package.namelist()
                for part in package.namelist():
                    ElementTree.fromstring(package.read(part))  # noqa: S314 - our own build output
        else:
            assert "$" not in content.decode("utf-8"), path


def test_bait_files_are_dated_by_their_names_and_folders_by_their_newest_file(
    tmp_path: Path,
) -> None:
    public = render(tmp_path) / "persona" / "public"

    def day(path: Path) -> str:
        return datetime.fromtimestamp(path.stat().st_mtime, UTC).date().isoformat()

    payments = public / "Finance" / "Payments"
    assert day(payments / "2026-11-06_fiskeroppgjor_uke45.csv") == "2026-11-06"
    assert day(payments) == "2026-11-06"
    assert day(public / "Scans" / "SCAN_20260914_101233.pdf") == "2026-09-14"
    assert day(public / "IT" / "VPN_access.txt") == "2025-06-12"
    newest = max(p.stat().st_mtime for p in public.rglob("*"))
    assert public.stat().st_mtime == newest
    hours = {datetime.fromtimestamp(p.stat().st_mtime, UTC).hour for p in public.rglob("*")}
    assert hours <= set(range(8, 17)), "office hours"


def test_every_bait_user_name_is_in_the_bait_files() -> None:
    users = json.loads((PERSONA / "persona.json").read_text(encoding="utf-8"))["bait_users"]
    text = "".join(p.read_text(encoding="utf-8") for p in BAIT.rglob("*") if p.is_file())
    for placeholder in users:
        assert f"${placeholder}" in text, placeholder


def luhn_valid(number: str) -> bool:
    digits = [int(d) for d in reversed(number)]
    doubled = [d * 2 - 9 if d * 2 > 9 else d * 2 for d in digits[1::2]]
    return (sum(digits[::2]) + sum(doubled)) % 10 == 0


def mod11_valid(number: str, weights: list[int]) -> bool:
    check = (11 - sum(int(d) * w for d, w in zip(number, weights, strict=False)) % 11) % 11
    return check == int(number[-1])


def test_bait_numbers_cannot_be_real() -> None:
    """Nothing in the bait is a real card, person, account or company.

    Card numbers fail the Luhn check or are documented test numbers.
    Personal numbers are synthetic: Skatteetaten's test population has 80
    added to the month, so no person has one. Account (1234.56.78901) and
    organisation numbers (123 456 789) fail their mod 11 check digit.
    """
    text = "\n".join(p.read_text(encoding="utf-8") for p in BAIT.rglob("*") if p.is_file())
    cards = [re.sub(r"\D", "", n) for n in re.findall(r"(?<!\d)(?:\d{4}[ -]?){3}\d{4}(?!\d)", text)]
    personal = re.findall(r"(?<![\d.])\d{6} ?\d{5}(?![\d.])", text)
    accounts = [n.replace(".", "") for n in re.findall(r"(?<!\d)\d{4}\.\d{2}\.\d{5}(?!\d)", text)]
    companies = [n.replace(" ", "") for n in re.findall(r"(?<!\d)\d{3} \d{3} \d{3}(?!\d)", text)]
    assert cards and all(n in TEST_CARDS or not luhn_valid(n) for n in cards), cards
    assert personal and all(81 <= int(n[2:4]) <= 92 for n in personal), personal
    assert accounts and not any(mod11_valid(n, ACCOUNT_WEIGHTS) for n in accounts), accounts
    assert companies and not any(mod11_valid(n, ORG_WEIGHTS) for n in companies), companies


@pytest.mark.skipif(sys.platform != "linux", reason="the supervisor runs on Linux")
def test_image_file_replaces_a_planted_link_and_never_fails_the_start(tmp_path: Path) -> None:
    supervisor = load("supervise")
    image = tmp_path / "image"
    image.write_text("0.2.0 2026-11-07T18:00:00Z\n")
    key = tmp_path / "ssh_host_key"
    key.write_text("key\n")
    copy = tmp_path / "copy"
    copy.symlink_to(key)

    supervisor.copy_image_file(image, copy)
    assert not copy.is_symlink()
    assert copy.read_text() == image.read_text()
    assert key.read_text() == "key\n"
    supervisor.copy_image_file(tmp_path / "missing", copy)


def supervise(commands: list[list[str]]) -> subprocess.Popen:
    code = f"import sys, supervise; sys.exit(supervise.run({commands!r}))"
    return subprocess.Popen([sys.executable, "-c", code], cwd=CANARY)


@pytest.mark.skipif(sys.platform != "linux", reason="the supervisor runs on Linux")
def test_supervisor_stops_everything_when_one_daemon_exits() -> None:
    process = supervise([["sleep", "60"], ["true"], ["sleep", "60"]])
    assert process.wait(timeout=10) == 1


@pytest.mark.skipif(sys.platform != "linux", reason="the supervisor runs on Linux")
def test_supervisor_stops_cleanly_on_request() -> None:
    process = supervise([["sleep", "60"], ["sleep", "60"]])
    children = Path(f"/proc/{process.pid}/task/{process.pid}/children")
    deadline = time.monotonic() + 10
    while len(children.read_text().split()) < 2 and time.monotonic() < deadline:
        time.sleep(0.05)
    process.send_signal(signal.SIGTERM)
    # The daemons must receive the SIGTERM that the supervisor itself blocks.
    assert process.wait(timeout=10) == 0
