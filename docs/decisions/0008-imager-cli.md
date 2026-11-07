# 0008: Writing cards through the Raspberry Pi Imager CLI

Status: accepted, 2026-11-07

## Context

DeKoi Setup must not write disks itself (`docs/PROMPT.md` 3.3). Phase 6
started with a spike on Raspberry Pi Imager 2.0.8, read from its help, its
installed files and its source at tag `v2.0.8` (`src/cli.cpp`, `main.cpp`,
`downloadthread.cpp`, `downloadextractthread.cpp`,
`customization_generator.cpp`, `windows/platformquirks_windows.cpp`,
`drivelist/drivelist_windows.cpp`). Nothing was written to any disk; the
first real write is in Phase 7.

## Findings

- Flags: `--cli`, `--sha256 <hex>`, `--cloudinit-userdata <file>`,
  `--cloudinit-networkconfig <file>`, `--disable-eject`, `--disable-verify`,
  `--debug`, `--quiet`, `--log-file <path>`, `--cache-file`,
  `--first-run-script`, `--secure-boot-key`,
  `--enable-writing-system-drives`; positional `src` (file or http(s) URL)
  and `dst`. Read-back verification is on unless `--disable-verify`.
- `--sha256` is compared with the uncompressed data written for a single
  compressed image such as our `.img.xz` (`_writehash`); only multi-file zip
  archives are checked against the download. So it takes
  `extract_sha256` from `device/image.json`.
- Destination check: unless `--enable-writing-system-drives` is given, the
  CLI refuses any `dst` that is not in its own list of removable drives. On
  Windows `dst` is `\\.\PhysicalDriveN`.
- Elevation (Windows): `rpi-imager.exe` has a `requireAdministrator`
  manifest and the CLI exits with "Not running as Administrator" otherwise.
  It must be started through ShellExecute with the `runas` verb, which shows
  the UAC prompt. `rpi-imager-cli.cmd` does this with `start /WAIT`, but cmd
  re-parses `%*`, which is unsafe for paths with `&`, `%` or `^`.
- Progress and errors: the CLI prints progress and `Error: ...` only to
  stderr. On Windows it attaches to the parent console or allocates a new
  one and reopens stderr on it, so an elevated child's output cannot be
  piped back to DeKoi Setup. `--log-file` only receives Qt debug messages,
  and CLI mode discards those unless `--debug` is also set. The exit code
  (0 success, 1 failure) does come back.
- Drive listing: `Get-Disk` works without elevation in Windows PowerShell
  5.1 (on every Windows 10 and 11) and reports `BusType`, `IsSystem`,
  `IsBoot`, `IsReadOnly`, `IsOffline` and the size. macOS:
  `diskutil list -plist external physical`; Linux: `lsblk -J -b` with
  `RM`, `HOTPLUG`, `TRAN` and `TYPE`.
- Installing: winget package `RaspberryPiFoundation.RaspberryPiImager`
  (2.0.8 today); elsewhere https://www.raspberrypi.com/software/.

## Decision

- DeKoi Setup passes the HTTPS URL from `device/image.json` and
  `--sha256 <extract_sha256>`. Imager downloads, decompresses, checks the
  hash and caches the image; DeKoi Setup has no download code.
- On Windows it starts `rpi-imager.exe --cli ...` with
  `Start-Process -Verb RunAs -Wait -PassThru` and reads the exit code. The
  PowerShell script is a constant; the executable and arguments reach it in
  environment variables, so no value is ever parsed as script. The
  arguments are a fixed list built in one place; the only user-dependent
  parts are paths in DeKoi Setup's own temporary directory and the drive
  number. It passes `--debug --log-file` and shows the log, collapsed, when
  the write fails. Progress is shown as "writing" with Imager's console
  window open next to it; no percentage.
- The card list shows only disks on a USB, SD or MMC bus that are not
  system, boot, read-only or offline disks. Imager's own removable-drive
  check stays on as a second, independent guard:
  `--enable-writing-system-drives` is never passed.

## Consequences

- No percentage in DeKoi Setup on Windows. Acceptable for the MVP; a later
  Imager with machine-readable progress would change that.
- One UAC prompt per card. DeKoi Setup itself runs unelevated.
- macOS and Linux use the same arguments; how each elevates (Imager's own
  elevation policy on macOS, `pkexec` or sudo on Linux) is best effort and
  untested in the MVP.
- If a future Imager changes `--sha256` semantics or flags, the write fails
  closed on the hash check; the version is checked in Phase 7.
