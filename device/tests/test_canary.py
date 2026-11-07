"""The canary image's own code: the persona renderer and the daemon supervisor."""

import importlib.util
import json
import re
import signal
import struct
import subprocess
import sys
import time
import zipfile
import zlib
from pathlib import Path
from types import ModuleType
from xml.etree import ElementTree

import pytest

CANARY = Path(__file__).parents[1] / "canary"
PERSONA = CANARY / "persona"
BAIT = PERSONA / "public"
CONFIGURATION = ["smb.conf", "avahi-daemon.conf", "skin/index.html"]
# Card numbers that payment processors document for testing; no card has them.
TEST_CARDS = {"4111111111111111", "5555555555554444"}


def load(name: str) -> ModuleType:
    spec = importlib.util.spec_from_file_location(name, CANARY / f"{name}.py")
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def render(tmp_path: Path) -> Path:
    command = [sys.executable, "-I", str(CANARY / "render.py"), "persona.json", str(tmp_path)]
    subprocess.run([*command, *CONFIGURATION, "public"], cwd=PERSONA, check=True)
    return tmp_path


def test_persona_fills_every_placeholder(tmp_path: Path) -> None:
    persona = json.loads((PERSONA / "persona.json").read_text())
    out = render(tmp_path)
    for template in CONFIGURATION:
        text = (out / template).read_text()
        assert "$" not in text, template
    assert f"netbios name = {persona['name']}" in (out / "smb.conf").read_text()
    assert f"host-name={persona['name']}" in (out / "avahi-daemon.conf").read_text()
    page = (out / "skin" / "index.html").read_text()
    assert f"<title>{persona['name']}</title>" in page
    assert persona["model"] in page
    assert persona["firmware"] in page


def test_login_page_posts_what_opencanary_reads(tmp_path: Path) -> None:
    page = (render(tmp_path) / "skin" / "index.html").read_text()
    assert '<form method="post" action="/index.html">' in page
    assert 'name="username"' in page
    assert 'name="password"' in page
    # OpenCanary shows the part between these markers only after a failed sign-in.
    assert page.count("<!--STARTERR-->") == page.count("<!--ENDERR-->") == 1
    # No external assets: nothing the page loads leaves the device.
    assert "http://" not in page
    assert "https://" not in page
    assert "<script" not in page


def test_bait_files_are_small_and_open_in_the_format_their_name_says(tmp_path: Path) -> None:
    formats = load("render").FORMATS
    public = render(tmp_path) / "public"
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
        elif path.suffix == ".png":
            width, height, length = struct.unpack(">II9xI", content[16:37])
            assert content.startswith(b"\x89PNG\r\n\x1a\n")
            assert len(zlib.decompress(content[41 : 41 + length])) == height * (1 + 3 * width)
        else:
            assert "$" not in content.decode("utf-8"), path


def test_every_bait_user_name_is_in_the_bait_files() -> None:
    users = json.loads((PERSONA / "persona.json").read_text())["bait_users"]
    text = "".join(p.read_text(encoding="utf-8") for p in BAIT.rglob("*") if p.is_file())
    for placeholder in users:
        assert f"${placeholder}" in text, placeholder


def test_bait_numbers_are_test_numbers() -> None:
    """Card numbers are documented test numbers, personal numbers synthetic.

    Synthetic Norwegian personal numbers (Skatteetaten's test population)
    have 80 added to the month, so no person has one.
    """
    text = "\n".join(p.read_text(encoding="utf-8") for p in BAIT.rglob("*") if p.is_file())
    numbers = [n.replace(" ", "") for n in re.findall(r"\d[\d ]{9,22}\d", text)]
    cards = {n for n in numbers if len(n) == 16}
    personal = [n for n in numbers if len(n) == 11]
    assert cards and cards <= TEST_CARDS
    assert personal and all(81 <= int(n[2:4]) <= 92 for n in personal), personal


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
