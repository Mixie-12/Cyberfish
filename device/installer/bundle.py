"""Builds the installer bundle (ADR 0031) inside its Containerfile.

bundle.py boot IMAGE_JSON CONFIG_TXT OUT
    Downloads the pinned image, proves it, checks that both kernels have
    what install.py needs built in, and copies the kernels, firmware and
    device trees from its boot partition to OUT.
bundle.py pack ROOTFS CARD ZIP
    Packs ROOTFS as the initramfs that CARD/config.txt names, into CARD,
    and zips CARD. Both are byte for byte the same for the same inputs.
"""

import gzip
import hashlib
import json
import lzma
import os
import re
import stat
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path
from typing import NoReturn

MIB = 1024 * 1024
SECTOR = 512
# What the Raspberry Pi 4, 400, 5 and 500 load besides the kernel: the Pi 4
# firmware (the Pi 5 has its own in EEPROM) and each board's device tree.
# The firmware's licence must travel with it.
FIRMWARE = (
    "LICENCE.broadcom",
    "start4.elf",
    "fixup4.dat",
    "bcm2711-rpi-4-b.dtb",
    "bcm2711-rpi-400.dtb",
    "bcm2712-rpi-5-b.dtb",
    "bcm2712d0-rpi-5-b.dtb",
    "bcm2712-d-rpi-5-b.dtb",
    "bcm2712-rpi-500.dtb",
)
# The initramfs carries no kernel modules (ADR 0032): everything install.py
# needs must be built into both kernels.
KERNEL_CONFIG = (
    "CONFIG_BLK_DEV_INITRD=y",
    "CONFIG_RD_GZIP=y",
    # The kernel's own initramfs then holds /dev/console.
    'CONFIG_INITRAMFS_SOURCE=""',
    "CONFIG_BINFMT_SCRIPT=y",
    "CONFIG_DEVTMPFS=y",
    "CONFIG_TMPFS=y",
    "CONFIG_PACKET=y",
    # Ethernet: Pi 4, then Pi 5 (RP1 on PCIe)
    "CONFIG_BCMGENET=y",
    "CONFIG_MDIO_BCM_UNIMAC=y",
    "CONFIG_BROADCOM_PHY=y",
    "CONFIG_PCIE_BRCMSTB=y",
    "CONFIG_MFD_RP1=y",
    "CONFIG_COMMON_CLK_RP1=y",
    "CONFIG_PINCTRL_RP1=y",
    "CONFIG_MACB=y",
    # Card slot: Pi 4, then Pi 5
    "CONFIG_MMC_BLOCK=y",
    "CONFIG_MMC_SDHCI_IPROC=y",
    "CONFIG_MMC_SDHCI_BRCMSTB=y",
    # FAT with its default code page and character set
    "CONFIG_VFAT_FS=y",
    "CONFIG_NLS_CODEPAGE_437=y",
    'CONFIG_FAT_DEFAULT_IOCHARSET="ascii"',
    "CONFIG_NLS_ASCII=y",
    # Activity LED
    "CONFIG_LEDS_GPIO=y",
    "CONFIG_LEDS_TRIGGER_TIMER=y",
)


def fail(message: str) -> NoReturn:
    sys.exit(f"bundle.py: {message}")


def download(url: str, target: Path, size: int, sha256: str) -> None:
    digest = hashlib.sha256()
    with urllib.request.urlopen(url, timeout=60) as response, target.open("wb") as out:  # noqa: S310 - the pinned HTTPS URL
        while chunk := response.read(MIB):
            digest.update(chunk)
            out.write(chunk)
    if target.stat().st_size != size or digest.hexdigest() != sha256:
        fail(f"{url} does not have the pinned size and SHA-256")


def decompress(source: Path, target: Path, size: int, sha256: str) -> None:
    digest = hashlib.sha256()
    with lzma.open(source) as image, target.open("wb") as out:
        while chunk := image.read(MIB):
            digest.update(chunk)
            out.write(chunk)
    if target.stat().st_size != size or digest.hexdigest() != sha256:
        fail(f"{source} does not decompress to the pinned size and SHA-256")


def partition_offsets(image: Path) -> tuple[int, int]:
    """Byte offsets of the first two partitions in the MBR."""
    with image.open("rb") as disk:
        mbr = disk.read(SECTOR)
    starts = [int.from_bytes(mbr[454 + 16 * i : 458 + 16 * i], "little") for i in (0, 1)]
    return starts[0] * SECTOR, starts[1] * SECTOR


def debugfs(image: Path, offset: int, command: str) -> str:
    result = subprocess.run(  # noqa: S603 - fixed program, paths from this build
        ["debugfs", "-R", command, f"{image}?offset={offset}"],  # noqa: S607 - from the build image
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout


def check_kernels(image: Path, offset: int, kernels: list[str]) -> None:
    """Fails unless each kernel's config in /boot has every KERNEL_CONFIG line."""
    names = [
        line.split("/")[5] for line in debugfs(image, offset, "ls -p /boot").splitlines() if line
    ]
    configs = sorted(name for name in names if name.startswith("config-"))
    if len(configs) != len(kernels):
        fail(f"{len(kernels)} kernels in config.txt, but /boot holds {configs}")
    for config in configs:
        lines = set(debugfs(image, offset, f"cat /boot/{config}").splitlines())
        missing = [line for line in KERNEL_CONFIG if line not in lines]
        if missing:
            fail(f"{config} lacks {missing}; the installer needs them built in")
        print(f"bundle.py: {config} has what the installer needs")


def config_names(config_txt: Path) -> tuple[list[str], str]:
    """The kernels and the initramfs that config.txt names."""
    config = config_txt.read_text()
    kernels = re.findall(r"^kernel=(\S+)$", config, re.MULTILINE)
    initramfs = re.findall(r"^initramfs (\S+) followkernel$", config, re.MULTILINE)
    if not kernels or len(initramfs) != 1:
        fail(f"{config_txt} must name the kernels and one initramfs")
    return kernels, initramfs[0]


def boot(image_json: Path, config_txt: Path, out: Path) -> None:
    pinned = json.loads(image_json.read_text())
    kernels, _ = config_names(config_txt)
    out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as work:
        compressed, image = Path(work, "image.img.xz"), Path(work, "image.img")
        download(pinned["url"], compressed, pinned["download_size"], pinned["download_sha256"])
        decompress(compressed, image, pinned["extract_size"], pinned["extract_sha256"])
        compressed.unlink()
        boot_offset, root_offset = partition_offsets(image)
        check_kernels(image, root_offset, kernels)
        for name in (*kernels, *FIRMWARE):
            subprocess.run(  # noqa: S603 - fixed program, names from this file and config.txt
                ["mcopy", "-n", "-i", f"{image}@@{boot_offset}", f"::{name}", str(out / name)],  # noqa: S607
                check=True,
                env={**os.environ, "MTOOLS_SKIP_CHECK": "1"},
            )


def padded(data: bytes) -> bytes:
    return data + bytes(-len(data) % 4)


def newc_entry(name: str, inode: int, mode: int, data: bytes) -> bytes:
    """One entry of the kernel's initramfs format ("newc", Documentation/
    driver-api/early-userspace/buffer-format.rst), owned by root, dated 1970."""
    fields = (inode, mode, 0, 0, 1, 0, len(data), 0, 0, 0, 0, len(name) + 1, 0)
    header = b"070701" + b"".join(b"%08X" % field for field in fields)
    return padded(header + name.encode() + b"\0") + padded(data)


def initramfs(rootfs: Path) -> bytes:
    """rootfs as a gzip-compressed newc archive, parents before children."""
    archive = bytearray()
    paths = sorted(rootfs.rglob("*"), key=lambda path: path.relative_to(rootfs).parts)
    for inode, path in enumerate(paths, start=1):
        mode = path.lstat().st_mode
        if stat.S_ISLNK(mode):
            data = os.readlink(path).encode()
        elif stat.S_ISREG(mode):
            data = path.read_bytes()
        elif stat.S_ISDIR(mode):
            data = b""
        else:
            fail(f"{path} is not a file, directory or link")
        archive += newc_entry(path.relative_to(rootfs).as_posix(), inode, mode, data)
    archive += newc_entry("TRAILER!!!", 0, 0, b"")
    return gzip.compress(bytes(archive), compresslevel=9, mtime=0)


def pack(rootfs: Path, card: Path, bundle: Path) -> None:
    _, name = config_names(card / "config.txt")
    (card / name).write_bytes(initramfs(rootfs))
    bundle.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(bundle, "w") as archive:
        for path in sorted(card.iterdir()):
            # The default date, 1980-01-01, and fixed permissions.
            entry = zipfile.ZipInfo(path.name)
            entry.external_attr = 0o644 << 16
            archive.writestr(entry, path.read_bytes(), zipfile.ZIP_DEFLATED, 9)
            print(f"bundle.py: {path.name} {path.stat().st_size}")


def main() -> None:
    match sys.argv[1:]:
        case ["boot", image_json, config_txt, out]:
            boot(Path(image_json), Path(config_txt), Path(out))
        case ["pack", rootfs, card, bundle]:
            pack(Path(rootfs), Path(card), Path(bundle))
        case _:
            fail(__doc__ or "")


if __name__ == "__main__":
    main()
