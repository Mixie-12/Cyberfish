# 0014: Writing the card from the phone

Status: superseded by 0031, 2026-11-08. Accepted 2026-11-07 and updated
the same day with the findings from building it, and for ADR 0017, which
replaced ADR 0012's generic card.

## Context

The owner wants setup without a computer and without weakening security.
Browsers cannot write cards. Android can, through a USB card reader on the
phone's USB-C port, using the USB host API with a user-space mass-storage
driver (no root), as EtchDroid does.

## Decision

- The app offers "Write a card with this phone" next to DeKoi Setup. It
  writes the same Raspberry Pi OS image as DeKoi Setup, from the URL, sizes
  and SHA-256 values in `device/image.json` (generated into the app at
  build time, one source of truth), plus the same cloud-init files with the
  card's claim code (ADR 0017), rendered by code shared with DeKoi Setup in
  `clients/shared`.
- Libraries: libaums 0.10.0 (Apache-2.0, user-space USB mass storage and
  FAT32) and XZ for Java 1.12 (0BSD since 1.10, public domain before;
  streaming xz decoding). Both are pinned by exact version in
  `clients/gradle/libs.versions.toml` like every other dependency. The
  repository has no Gradle dependency verification metadata, so nothing
  checks their hashes at build time; the SHA-256 of each file is in the
  commit that adds them.
- Integrity: the download is streamed through SHA-256 and xz onto the card.
  The first MiB, which holds the partition table, is zeroed first and
  written last, only once the download matches its pinned size and SHA-256,
  so a card left by any failure cannot start. The whole image is then read
  back and hashed against the extracted SHA-256, and the four boot files
  are read back. Any mismatch fails the card; the owner starts again (no
  resume).
- Safety: the owner picks the reader (Android's USB permission prompt),
  sees its name and size, and confirms "Erase this card"; touches through
  another app's window are ignored there. The app writes only USB mass
  storage (SCSI, bulk-only) with 512-byte sectors and 7.0 to 270 billion
  bytes (what 8 to 256 GB cards hold), and refuses a reader holding two
  cards. The phone's own storage is never a USB device.
- The write runs in a foreground service of type `connectedDevice`, whose
  prerequisite is the USB permission, with a partial wake lock.

## Findings while building it

- libaums 0.10.0 (March 2023) is the latest release; later fixes are
  unreleased. The app uses its SCSI block device and FAT32 classes
  directly, as `UsbMassStorageDevice.init()` refuses a card without a
  partition table it knows. It reports the last block address as the block
  count (sizes one block short, which only makes the gate stricter), and
  does not retry UNIT ATTENTION after a card is inserted (the app retries
  once). Its REQUEST SENSE always fails, so the app carries the fixed
  SCSI block device (ADR 0029).
- Its two JNI helpers (`liberrno-lib.so`, `libusb-lib.so`) are built for
  4 KB pages; Android lint warns (`Aligned16KB`). On a phone with 16 KB
  pages, Android 16 and later run the whole app in page-size compatibility
  mode and warn at first start; where that mode is off, the helpers do not
  load. libaums catches the failed load, but its recovery from a stalled
  transfer then calls the native clear-halt unchecked and the write
  crashes, leaving a card that cannot start. Not tested on such a phone.
  Fix: a libaums release built for 16 KB pages, or our own build of the
  two helpers. Google Play requires 16 KB support from 1 February 2027.
- As Imager 2.0.8 does for `cloudinit-rpi`, the app writes `meta-data` with
  a new instance ID and adds `ds=nocloud;i=<id>` to `cmdline.txt`. Imager
  also zeroes the card's last MiB (a stale GPT backup); the app does not,
  as 0.10.0 misreports the last block. The phone writes the cable network
  configuration only.

## Consequences

- The phone needs a USB-C card reader and downloads about 550 MB; 3 GB is
  written and read back. Progress is shown in plain words.
- Our code now includes a raw block writer, which ADR 0008 avoided on the
  desktop by using Imager. It is limited to removable USB mass storage
  behind the owner's explicit choice, and its verification is mandatory.
