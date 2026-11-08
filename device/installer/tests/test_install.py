"""The parts of the installer that run without a Raspberry Pi: what it reads
from the card, how it proves the download, and how it writes and checks the
image, with a file standing in for the card."""

import functools
import hashlib
import http.server
import lzma
import os
import ssl
import threading
import urllib.error
import urllib.request
from collections.abc import Iterator
from pathlib import Path

import pytest

import install

DEVICE = Path(__file__).parents[2]
MIB = install.MIB


def xz(data: bytes) -> bytes:
    # The fastest preset: these tests are about the bytes, not the ratio.
    return lzma.compress(data, preset=0)


def test_the_pinned_image_loads_and_is_served_over_https() -> None:
    image = install.Image.load(DEVICE / "image.json")
    assert image.url.startswith("https://")
    assert len(image.download_sha256) == len(image.extract_sha256) == 64
    assert image.extract_size % MIB == 0


def test_the_instance_id_is_read_from_the_meta_data_the_app_writes() -> None:
    assert install.instance_id(b"instance-id: dekoi-1762560000000\n") == "dekoi-1762560000000"


@pytest.mark.parametrize(
    "meta_data",
    [
        b"instance-id: dekoi-1\r\n",
        b"instance-id: dekoi-1",
        b"instance-id: Dekoi-1\n",
        b"instance-id: dekoi 1\n",
        b"instance-id: dekoi-1\nlocal-hostname: x\n",
        b"",
    ],
)
def test_other_meta_data_is_refused(meta_data: bytes) -> None:
    with pytest.raises(install.Failure):
        install.instance_id(meta_data)


def test_the_command_line_gets_the_data_source_as_the_app_adds_it() -> None:
    current = "console=serial0,115200 console=tty1 root=PARTUUID=27b94958-02 rootwait resize\n"
    assert install.command_line(current, "dekoi-7") == (
        "console=serial0,115200 console=tty1 root=PARTUUID=27b94958-02 rootwait resize"
        " ds=nocloud;i=dekoi-7"
    )


@pytest.mark.parametrize("current", ["", " \n", "a\nb\n"])
def test_a_command_line_that_is_not_one_line_is_refused(current: str) -> None:
    with pytest.raises(install.Failure):
        install.command_line(current, "dekoi-7")


def write_cloud_init(folder: Path, meta_data: bytes = b"instance-id: dekoi-7\n") -> None:
    folder.mkdir()
    (folder / "user-data").write_bytes(b"#cloud-config\n")
    (folder / "network-config").write_bytes(b"network:\n  version: 2\n")
    (folder / "meta-data").write_bytes(meta_data)


def test_the_cloud_init_files_are_read_as_they_are(tmp_path: Path) -> None:
    write_cloud_init(tmp_path / "dekoi")
    assert install.read_cloud_init(tmp_path / "dekoi") == {
        "user-data": b"#cloud-config\n",
        "network-config": b"network:\n  version: 2\n",
        "meta-data": b"instance-id: dekoi-7\n",
    }


def test_a_missing_cloud_init_file_stops_the_installer(tmp_path: Path) -> None:
    write_cloud_init(tmp_path / "dekoi")
    (tmp_path / "dekoi" / "network-config").unlink()
    with pytest.raises(install.Failure, match="network-config is missing"):
        install.read_cloud_init(tmp_path / "dekoi")


def test_an_oversized_cloud_init_file_stops_the_installer(tmp_path: Path) -> None:
    write_cloud_init(tmp_path / "dekoi")
    (tmp_path / "dekoi" / "user-data").write_bytes(bytes(install.CLOUD_INIT_LIMIT + 1))
    with pytest.raises(install.Failure, match="larger than"):
        install.read_cloud_init(tmp_path / "dekoi")


def test_malformed_meta_data_stops_the_installer_before_the_network(tmp_path: Path) -> None:
    write_cloud_init(tmp_path / "dekoi", meta_data=b"instance-id: x\ny\n")
    with pytest.raises(install.Failure, match="meta-data"):
        install.read_cloud_init(tmp_path / "dekoi")


@pytest.fixture
def server(tmp_path: Path) -> Iterator[tuple[str, Path]]:
    """A local HTTP server for the files in a folder: its URL and the folder."""
    folder = tmp_path / "served"
    folder.mkdir()
    handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=folder)
    with http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler) as httpd:
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        yield f"http://127.0.0.1:{httpd.server_address[1]}", folder
        httpd.shutdown()


def fetch(url: str, target: Path, size: int, sha256: str) -> bool:
    with urllib.request.urlopen(url, timeout=10) as response, target.open("wb") as out:  # noqa: S310 - local test server
        return install.save(response, out, size, sha256)


def test_the_download_is_saved_when_it_has_the_pinned_size_and_hash(
    server: tuple[str, Path], tmp_path: Path
) -> None:
    url, folder = server
    data = xz(os.urandom(3 * MIB + 5))
    (folder / "image.img.xz").write_bytes(data)
    target = tmp_path / "download"
    sha256 = hashlib.sha256(data).hexdigest()
    assert fetch(f"{url}/image.img.xz", target, len(data), sha256)
    assert target.read_bytes() == data


@pytest.mark.parametrize("change", ["hash", "shorter", "longer"])
def test_a_download_unlike_the_pin_is_not_proven(
    server: tuple[str, Path], tmp_path: Path, change: str
) -> None:
    url, folder = server
    data = xz(os.urandom(2 * MIB))
    (folder / "image.img.xz").write_bytes(data)
    size, sha256 = len(data), hashlib.sha256(data).hexdigest()
    if change == "hash":
        sha256 = hashlib.sha256(b"other").hexdigest()
    elif change == "shorter":
        size -= 1
    else:
        size += 1
    target = tmp_path / "download"
    assert not fetch(f"{url}/image.img.xz", target, size, sha256)
    assert target.stat().st_size <= size


def test_the_installer_cannot_download_over_plain_http(server: tuple[str, Path]) -> None:
    url, _ = server
    opener = install.https_opener(ssl.create_default_context())
    with pytest.raises(urllib.error.URLError, match="unknown url type"):
        opener.open(f"{url}/image.img.xz", timeout=10)


def test_failed_attempts_are_repeated_after_growing_pauses(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    pauses: list[float] = []
    monkeypatch.setattr(install.time, "sleep", pauses.append)
    outcomes = [OSError("no lease"), install.Mismatch("hash"), None]

    def attempt() -> None:
        outcome = outcomes.pop(0)
        if outcome is not None:
            raise outcome

    install.until_done("trying", attempt)
    assert pauses == [5, 10]
    assert outcomes == []


def test_a_failure_is_not_repeated() -> None:
    def attempt() -> None:
        raise install.Failure("card too small")

    with pytest.raises(install.Failure):
        install.until_done("trying", attempt)


CARD_FILL = b"\xaa"


def prepare(tmp_path: Path, data: bytes, compressed: bytes | None = None) -> tuple[Path, Path]:
    """An xz file of data, or the given compressed bytes, and a card larger
    than the image."""
    source = tmp_path / "image.img.xz"
    source.write_bytes(xz(data) if compressed is None else compressed)
    card = tmp_path / "card"
    card.write_bytes(CARD_FILL * (len(data) + MIB))
    return source, card


def test_the_image_is_written_and_reads_back(tmp_path: Path) -> None:
    data = os.urandom(3 * MIB)
    source, card = prepare(tmp_path, data)
    install.write_image(source, card, len(data))
    written = card.read_bytes()
    assert written[: len(data)] == data
    assert written[len(data) :] == CARD_FILL * MIB
    assert install.verify(card, len(data), hashlib.sha256(data).hexdigest())
    assert not install.verify(card, len(data), hashlib.sha256(b"other").hexdigest())


def test_a_card_shorter_than_the_image_does_not_verify(tmp_path: Path) -> None:
    card = tmp_path / "card"
    card.write_bytes(bytes(MIB))
    assert not install.verify(card, 2 * MIB, hashlib.sha256(bytes(2 * MIB)).hexdigest())


@pytest.mark.parametrize("pinned", [2 * MIB, 4 * MIB])
def test_an_image_of_another_size_leaves_no_partition_table(tmp_path: Path, pinned: int) -> None:
    data = os.urandom(3 * MIB)
    source, card = prepare(tmp_path, data)
    with pytest.raises(install.Failure):
        install.write_image(source, card, pinned)
    written = card.read_bytes()
    assert written[:MIB] == bytes(MIB)
    assert written[MIB : 2 * MIB] == data[MIB : 2 * MIB]


def test_a_broken_stream_leaves_no_partition_table(tmp_path: Path) -> None:
    data = os.urandom(3 * MIB)
    compressed = xz(data)
    source, card = prepare(tmp_path, data, compressed[: len(compressed) * 3 // 4])
    with pytest.raises(EOFError):
        install.write_image(source, card, len(data))
    written = card.read_bytes()
    assert written[:MIB] == bytes(MIB)
    assert written[MIB : 2 * MIB] == data[MIB : 2 * MIB]
