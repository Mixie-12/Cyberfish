# 0031: The phone prepares a card that installs itself

Status: accepted, 2026-11-08. Replaces the raw card writer of ADR 0014 and
the driver of ADR 0029.

## Context

The phone wrote the 3 GB image itself, over USB, with libaums (ADR 0014,
0029). On the owner's phone with a cheap reader it never finished: the
reader hung at random, refused resets, and libaums 0.10.0's native helpers
cannot load. EtchDroid's author reports the same for every phone and
reader ("they're all unreliable") and answers with unplug-and-resume.
Android gives apps no raw block access to USB storage, but it mounts a
card's FAT file system with the kernel's own driver and lets the owner
grant an app a folder on it (Storage Access Framework). The Raspberry Pi 4
and 5 boot from a FAT12/16/32 partition only, not exFAT (Raspberry Pi
documentation, `autoboot.txt`).

## Decision

The phone copies a small installer onto the card as files; the Raspberry
Pi then downloads, verifies and writes Raspberry Pi OS itself.

- Card, FAT32, root of the first partition: the installer bundle's files,
  and `dekoi/user-data`, `dekoi/network-config`, `dekoi/meta-data`, the
  same cloud-init files as before (`CardContent.userData`,
  `CloudInit.networkConfig(Cable)`, `CloudInit.metaData`).
- Installer bundle: a zip whose entries go to the card root. Raspberry Pi
  firmware, device trees and the two kernels (`kernel8.img` for the Pi 4,
  `kernel_2712.img` for the Pi 5) are copied unchanged from the boot
  partition of the image pinned in `device/image.json`; `config.txt`,
  `cmdline.txt` and one initramfs are DeKoi's. The initramfs holds Alpine
  Linux's busybox, Python 3 and CA certificates, the kernels' modules
  that the installer needs, and the installer, `device/installer/`,
  standard-library Python. The image's URL, sizes and SHA-256 values are
  built into it from `device/image.json`.
- On the Pi the installer, running from RAM: reads `dekoi/` into memory;
  brings up Ethernet with DHCP; sets the clock by NTP; downloads the image
  over HTTPS into RAM and checks its size and SHA-256, retrying until it
  matches, before it touches the card; writes it with the first MiB
  zeroed first and written last; reads the image back against the
  extracted SHA-256; writes the three cloud-init files into the new boot
  partition and adds `ds=nocloud;i=<instance-id>` to its `cmdline.txt`,
  as Raspberry Pi Imager does; and reboots into Raspberry Pi OS. The
  activity LED shows working and failed.
- Hosting: CI builds the bundle and pushes it with ORAS to
  `ghcr.io/mixie-12/dekoi-installer` (public, like the device images). Its
  single layer's digest is the zip's SHA-256. `device/installer.json` pins
  the repository, digest and size; the apps read it at build time, as
  they read `image.json`. The phone fetches the blob by digest with an
  anonymous pull token and checks size and SHA-256 before using it.
- Phone: the owner connects the card in any reader, or the phone's own
  slot. Android mounts it; the owner chooses it in Android's picker
  (`StorageVolume.createOpenDocumentTreeIntent`) and confirms that it is
  erased. The app deletes what is on it, copies the bundle and the
  `dekoi/` files, reads every file back against its hash, and links the
  claim code as before (ADR 0017). Cards of 8 to 32 GB: larger cards come,
  and Android formats them, as exFAT, which the Pi cannot boot. The app
  checks the capacity and says so.
- Removed: libaums, XZ for Java, the SCSI driver, the USB permission flow,
  the `connectedDevice` foreground service and the USB attach filter.

## Consequences

- The phone does only small file copies through Android's own storage
  stack; any reader Android can open works, and so does a phone's own
  card slot. The phone downloads about the bundle's size, not 550 MB.
- The Pi needs the network cable during installation (the phone already
  set up only the cable) and takes about 10 minutes; a failed install
  leaves either the installer, which tries again on the next start, or,
  once writing began, a card without a partition table.
- Cards over 32 GB need DeKoi Setup on a computer, or formatting as FAT32
  there first.
- Found while building the phone side: on Android 11 and later the picker
  does not offer the root of a card in the phone's own slot
  (`ExternalStorageProvider` allows a volume's root on USB only), so those
  phones need a USB card reader, and the app asks for one. A card that
  still holds Raspberry Pi OS shows Android only its 512 MB boot
  partition (vold mounts FAT, exFAT and NTFS partitions only); the app
  refuses it as too small and asks the owner to format it in the phone's
  storage settings first. Neither is tried on a phone yet.
- A new package is made public once, by the owner, after its first push
  (`docs/runbooks/release.md`).
