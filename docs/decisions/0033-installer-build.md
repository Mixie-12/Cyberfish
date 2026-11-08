# 0033: What the installer carries and what it assumes

Status: accepted, 2026-11-08. Implements the Raspberry Pi side of ADR 0031.

## Context

The installer bundle is built from the image pinned in `device/image.json`
(Raspberry Pi OS of 2026-10-06, kernels 6.18.50 `rpi-v8` and `rpi-2712`)
and Alpine Linux 3.24. Reading both kernel configs from the image showed
that every driver the installer needs is built in, not a module: Ethernet
on the Pi 4 (`BCMGENET`) and Pi 5 (`PCIE_BRCMSTB`, `MFD_RP1`, `MACB`), both
card slots, `VFAT_FS` with code page 437 and ASCII, `PACKET` for DHCP,
`LEDS_GPIO` and the LED timer trigger, devtmpfs and tmpfs. All seven
initramfs compressions are enabled, and `INITRAMFS_SOURCE` is empty, so the
kernel's own initramfs supplies `/dev/console`.

## Decision

- No kernel modules in the initramfs and no module loading in the
  installer. `bundle.py` reads `/boot/config-*` of both kernels from the
  image and fails the build unless each has the lines in `KERNEL_CONFIG`.
  A later image that moves a driver into a module fails the build, not a Pi.
- `bundle.py` writes the initramfs (newc cpio, gzip) and the zip itself,
  with fixed owners, dates and permissions, so that the same inputs give the
  same bytes and no build step needs root.
- Alpine packages are installed for aarch64 with `apk --no-scripts` from the
  signed v3.24 index; `busybox`, `python3` and `ca-certificates-bundle` (the
  prebuilt CA file, as no install script runs) are pinned by version, and
  apk resolves their dependencies from the same index. The build's last
  stage unpacks the zip and starts its Python and busybox for arm64 under
  QEMU.
- The card is the `mmcblkN` disk whose first partition holds `dekoi/`.
- The download goes to its own tmpfs, sized to it, not to the initramfs
  root, whose default limit is half the memory. The installer stops before
  the network if the card is smaller than the image or the free memory is
  less than the download plus 128 MiB.
- The clock comes from `pool.ntp.org` by busybox `ntpd`, unauthenticated: it
  only makes TLS possible. The pinned SHA-256 proves the image. The HTTP
  client has no plain-HTTP handler, so a redirect cannot leave HTTPS.
- Activity LED by the kernel's timer trigger: 0.5 s on and off while
  working, 0.1 s after a stop. It keeps blinking while Python is busy.

## Consequences

- When Alpine replaces a pinned package version, the build fails until the
  pin is moved to the version in the index.
- Not verified on hardware: the drivers binding without modules, `eth0`
  and `mmcblk0` naming, the `ACT` LED name, Alpine's aarch64 binaries under
  the Pi 5's 16 KB page kernel, and a 1 GB board's free memory.
