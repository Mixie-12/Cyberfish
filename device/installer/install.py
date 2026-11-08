#!/usr/bin/python3 -I
"""The DeKoi installer (ADR 0031): init of the initramfs on the card the phone
prepared. On a Raspberry Pi 4 or 5 it downloads the pinned Raspberry Pi OS
image into RAM, proves it, writes it over the card it started from, adds the
card's cloud-init files and restarts into Raspberry Pi OS.

Until writing starts the card is untouched: network steps are retried, and
any other failure halts, so the next start tries again."""

import fcntl
import hashlib
import http.client
import json
import lzma
import os
import re
import ssl
import subprocess
import time
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO

BUSYBOX = "/bin/busybox"
IMAGE_JSON = Path("/usr/share/dekoi/image.json")
CA_FILE = "/etc/ssl/certs/ca-certificates.crt"
DHCP_SCRIPT = "/usr/share/udhcpc/default.script"
NTP_SERVER = "pool.ntp.org"
INTERFACE = "eth0"
CARD = Path("/mnt/card")
BOOT = Path("/mnt/boot")
DOWNLOAD = Path("/download/image.img.xz")
LEDS = (Path("/sys/class/leds/ACT"), Path("/sys/class/leds/led0"))
CLOUD_INIT = ("user-data", "network-config", "meta-data")
CLOUD_INIT_LIMIT = 1024 * 1024
MIB = 1024 * 1024
# The decompressor needs the image's 64 MiB dictionary; the rest is for Python
# and the kernel's write cache.
MEMORY_MARGIN = 128 * MIB
CARD_WAIT_SECONDS = 30
# Milliseconds on and off: an even blink while working, a fast one once stopped.
WORKING = (500, 500)
FAILED = (100, 100)
FIRST_DELAY = 5
LAST_DELAY = 300
# <linux/fs.h>
BLKRRPART = 0x125F  # _IO(0x12, 95): read the partition table again
BLKFLSBUF = 0x1261  # _IO(0x12, 97): write back and drop the device's cache


class Failure(Exception):
    """The installation cannot go on; the message says why."""


class Mismatch(Exception):
    """The download is not the pinned image; another attempt may get it."""


@dataclass(frozen=True)
class Image:
    """The image pinned in device/image.json."""

    url: str
    download_sha256: str
    download_size: int
    extract_sha256: str
    extract_size: int

    @classmethod
    def load(cls, path: Path) -> "Image":
        pinned = json.loads(path.read_text())
        return cls(
            url=pinned["url"],
            download_sha256=pinned["download_sha256"],
            download_size=pinned["download_size"],
            extract_sha256=pinned["extract_sha256"],
            extract_size=pinned["extract_size"],
        )


def log(message: str) -> None:
    print(f"dekoi-installer: {message}", flush=True)


def busybox(*arguments: str, timeout: float | None = None) -> None:
    subprocess.run([BUSYBOX, *arguments], check=True, timeout=timeout)  # noqa: S603 - fixed program and arguments


def mount(kind: str, source: str, target: Path, options: str) -> None:
    target.mkdir(parents=True, exist_ok=True)
    busybox("mount", "-t", kind, "-o", options, source, str(target))


def show(pattern: tuple[int, int]) -> None:
    """Blinks the activity LED by the kernel's timer trigger, which keeps
    blinking while the installer is busy. A missing LED does not stop it."""
    led = next((path for path in LEDS if path.is_dir()), None)
    if led is None:
        log("no activity LED")
        return
    try:
        (led / "trigger").write_text("timer")
        (led / "delay_on").write_text(str(pattern[0]))
        (led / "delay_off").write_text(str(pattern[1]))
    except OSError as error:
        log(f"cannot set the activity LED: {error}")


def progress(step: str, done: int, last: int, total: int) -> None:
    """Logs each tenth of total that the last bytes completed."""
    if (done - last) * 10 // total < done * 10 // total:
        log(f"{step} {done * 100 // total} %")


def until_done(step: str, attempt: Callable[[], None]) -> None:
    """Runs attempt until it succeeds, waiting longer after each failure. Only
    for steps that leave the card untouched."""
    delay = FIRST_DELAY
    while True:
        log(step)
        try:
            attempt()
        except (OSError, http.client.HTTPException, subprocess.SubprocessError, Mismatch) as error:
            log(f"{step} failed: {error}; trying again in {delay} s")
            time.sleep(delay)
            delay = min(2 * delay, LAST_DELAY)
        else:
            return


def instance_id(meta_data: bytes) -> str:
    """The instance ID from the meta-data the app writes (CloudInit.metaData)."""
    match = re.fullmatch(rb"instance-id: ([a-z0-9-]+)\n", meta_data)
    if match is None:
        raise Failure("dekoi/meta-data is not in the form the DeKoi app writes")
    return match.group(1).decode()


def command_line(current: str, instance: str) -> str:
    """cmdline.txt with the NoCloud data source added, as Raspberry Pi Imager
    and the app's CloudInit.commandLine do."""
    line = current.strip()
    if not line or "\n" in line:
        raise Failure("cmdline.txt must be one line")
    return f"{line} ds=nocloud;i={instance}"


def read_cloud_init(folder: Path) -> dict[str, bytes]:
    """The cloud-init files in the card's dekoi folder, checked."""
    files = {}
    for name in CLOUD_INIT:
        path = folder / name
        if not path.is_file():
            raise Failure(f"dekoi/{name} is missing; prepare the card again with the DeKoi app")
        if path.stat().st_size > CLOUD_INIT_LIMIT:
            raise Failure(f"dekoi/{name} is larger than {CLOUD_INIT_LIMIT} bytes")
        files[name] = path.read_bytes()
    instance_id(files["meta-data"])
    return files


def find_card() -> tuple[str, dict[str, bytes]]:
    """The disk the installer started from, known by the dekoi folder on its
    first partition, and the cloud-init files in that folder."""
    for _ in range(CARD_WAIT_SECONDS):
        for disk in sorted(os.listdir("/sys/block")):
            partition = f"/dev/{disk}p1"
            if not (re.fullmatch(r"mmcblk\d+", disk) and Path(partition).exists()):
                continue
            mount("vfat", partition, CARD, "ro,nosuid,nodev,noexec")
            try:
                if (CARD / "dekoi").is_dir():
                    return disk, read_cloud_init(CARD / "dekoi")
            finally:
                busybox("umount", str(CARD))
        time.sleep(1)
    raise Failure("no card with a dekoi folder; prepare the card again with the DeKoi app")


def available_memory() -> int:
    match = re.search(r"^MemAvailable:\s+(\d+) kB$", Path("/proc/meminfo").read_text(), re.M)
    if match is None:
        raise Failure("the kernel does not report the available memory")
    return int(match.group(1)) * 1024


def check_room(disk: str, image: Image) -> None:
    card = int(Path(f"/sys/block/{disk}/size").read_text()) * 512
    if card < image.extract_size:
        raise Failure(f"the card holds {card} bytes; the image needs {image.extract_size}")
    memory, needed = available_memory(), image.download_size + MEMORY_MARGIN
    if memory < needed:
        raise Failure(f"{memory} bytes of memory free; the installer needs {needed}")


def connect() -> None:
    busybox("ip", "link", "set", INTERFACE, "up")
    busybox("udhcpc", "-i", INTERFACE, "-f", "-q", "-n", "-t", "5", "-s", DHCP_SCRIPT, timeout=120)


def set_clock() -> None:
    # TLS needs the date. The time is not authenticated; the pinned SHA-256,
    # not the certificate, proves the image.
    busybox("ntpd", "-n", "-q", "-p", NTP_SERVER, timeout=60)


def https_opener(context: ssl.SSLContext) -> urllib.request.OpenerDirector:
    """An opener without plain HTTP, so that no redirect can fall back to it."""
    opener = urllib.request.OpenerDirector()
    for handler in (
        urllib.request.HTTPSHandler(context=context),
        urllib.request.HTTPRedirectHandler(),
        urllib.request.HTTPDefaultErrorHandler(),
        urllib.request.HTTPErrorProcessor(),
        urllib.request.UnknownHandler(),
    ):
        opener.add_handler(handler)
    return opener


def save(source: BinaryIO, target: BinaryIO, size: int, sha256: str) -> bool:
    """Copies source to target, stopping after size bytes, and says whether it
    was exactly size bytes with that SHA-256."""
    digest = hashlib.sha256()
    done = 0
    while chunk := source.read(MIB):
        done += len(chunk)
        if done > size:
            return False
        digest.update(chunk)
        target.write(chunk)
        progress("downloaded", done, len(chunk), size)
    return done == size and digest.hexdigest() == sha256


def download(opener: urllib.request.OpenerDirector, image: Image) -> None:
    with opener.open(image.url, timeout=60) as response, DOWNLOAD.open("wb") as target:
        if not save(response, target, image.download_size, image.download_sha256):
            raise Mismatch("the download does not have the pinned size and SHA-256")


def write_image(source: Path, card: Path, size: int) -> None:
    """Decompresses source onto card. The first MiB, which holds the partition
    table, is zeroed first and written last, so a card left by any failure
    cannot start."""
    with lzma.open(source) as image, card.open("r+b") as target:
        head = image.read(MIB)
        target.write(bytes(len(head)))
        done = len(head)
        while chunk := image.read(MIB):
            done += len(chunk)
            if done > size:
                raise Failure("the image is larger than pinned")
            target.write(chunk)
            progress("written", done, len(chunk), size)
        if done != size:
            raise Failure("the image is smaller than pinned")
        target.flush()
        os.fsync(target.fileno())
        target.seek(0)
        target.write(head)
        target.flush()
        os.fsync(target.fileno())


def verify(card: Path, size: int, sha256: str) -> bool:
    """Whether the first size bytes of card have that SHA-256."""
    digest = hashlib.sha256()
    done = 0
    with card.open("rb") as source:
        while done < size:
            chunk = source.read(min(MIB, size - done))
            if not chunk:
                return False
            digest.update(chunk)
            done += len(chunk)
            progress("checked", done, len(chunk), size)
    return digest.hexdigest() == sha256


def block_ioctl(device: Path, request: int) -> None:
    with device.open("rb") as handle:
        fcntl.ioctl(handle, request)


def add_cloud_init(partition: Path, files: dict[str, bytes]) -> None:
    mount("vfat", str(partition), BOOT, "rw,nosuid,nodev,noexec")
    for name, content in files.items():
        (BOOT / name).write_bytes(content)
    cmdline = BOOT / "cmdline.txt"
    current = cmdline.read_text(encoding="utf-8")
    cmdline.write_text(command_line(current, instance_id(files["meta-data"])), encoding="utf-8")
    busybox("umount", str(BOOT))


def install() -> None:
    mount("proc", "proc", Path("/proc"), "nosuid,nodev,noexec")
    mount("sysfs", "sysfs", Path("/sys"), "nosuid,nodev,noexec")
    mount("devtmpfs", "devtmpfs", Path("/dev"), "nosuid,noexec")
    # The DHCP client's script calls applets by name.
    busybox("--install", "-s")
    show(WORKING)
    image = Image.load(IMAGE_JSON)
    disk, files = find_card()
    card = Path("/dev") / disk
    log(f"started from {card}")
    check_room(disk, image)
    mount("tmpfs", "tmpfs", DOWNLOAD.parent, f"size={image.download_size + MIB},mode=0700")
    until_done("connecting by cable", connect)
    until_done("setting the clock", set_clock)
    opener = https_opener(ssl.create_default_context(cafile=CA_FILE))
    until_done(f"downloading {image.url}", lambda: download(opener, image))
    log(f"writing {card}; from now on a failure leaves a card that does not start")
    write_image(DOWNLOAD, card, image.extract_size)
    block_ioctl(card, BLKFLSBUF)
    if not verify(card, image.extract_size, image.extract_sha256):
        raise Failure("the card does not read back as the image; it may be faulty")
    block_ioctl(card, BLKRRPART)
    add_cloud_init(Path(f"/dev/{disk}p1"), files)
    os.sync()
    log("done; starting Raspberry Pi OS")
    busybox("reboot", "-f")


def main() -> None:
    try:
        install()
    except Exception as error:  # noqa: BLE001 - init must not end: the kernel would panic and the LED stop
        log(f"stopped: {error!r}")
        show(FAILED)
    while True:
        time.sleep(3600)


if __name__ == "__main__":
    main()
