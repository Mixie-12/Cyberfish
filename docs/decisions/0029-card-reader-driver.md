# 0029: The card reader driver carries libaums' unreleased fix

Status: accepted, 2026-11-07

## Context

Writing a card from the phone (ADR 0014) closed the app on the owner's
phone each time a USB-C card reader was chosen. The crash log showed
`IllegalArgumentException: newLimit > capacity: (36 > 18)` inside libaums'
`ScsiBlockDevice`. In libaums 0.10.0, the latest release (March 2023), every
REQUEST SENSE fails: after the first 18 bytes arrive it sets the buffer's
limit from the position they moved to plus the length the sense data
reports. A reader sends sense data whenever a command fails, and readers do
that in normal use: for an empty slot, and once after a card is put in.
Upstream fixed it in d78996b and fae7261 (2025), with no release since.

A second crash, `setGraph() before getGraph()`, came from the app itself:
the onboarding and notification effects could navigate before the
navigation graph existed.

## Decision

- The app carries one file, `ScsiBlockDevice.kt`: libaums' class at
  fae7261 under its Apache-2.0 header, used by `openCard` instead of
  `BlockDeviceDriverFactory`. Everything else (USB transfers, SCSI command
  classes, FAT32) stays libaums 0.10.0. Changed beyond upstream: reads and
  writes go in commands of at most 240 blocks (120 KiB), as Linux's
  usb-storage sends them to every USB mass storage device because "some
  devices are known to choke with anything larger" (Windows sends 128,
  macOS 256 over USB 2); libaums sends the app's 1 MiB chunk as one
  command and one USB transfer. A retried
  command starts from the buffer it was given; a transfer that moves
  nothing ends the data phase; the status is read even after short data;
  unit attention is retried once when starting and is an error during a
  write, where it means the card may have changed; the `InitRequired`
  branch, which libaums never triggers, is gone.
- Not chosen: a build of libaums from git (a new repository and build
  service in the supply chain), or patching around the bug in a
  `UsbCommunication` wrapper (it would depend on libaums' internals).
- `ScsiBlockDeviceTest` simulates a reader over bulk-only transport,
  answering REQUEST SENSE as real readers do: an empty slot, a card just
  put in, a card becoming ready, a write-protected card, and blocks written
  and read back.
- Every failure from the reader, a runtime exception included, becomes
  "this card could not be written" (`onCard`), and its cause is logged
  under the tag `DeKoiCard` and shown under "Technical details" on the
  failure screen, collapsed. The write stops with the partition table
  zeroed, as before.
- Found on the owner's phone with v0.2.3: each write stopped about a
  second after it started, six times, and the phone's log had already
  dropped the app's lines. The 240-block limit is the fix for the most
  likely cause; the details on the screen say what happened if it is not.
- Found with v0.2.4: writes ran 7 to 34 seconds, then stopped with "Could
  not read from device, result == -1 errno 0" behind "The reader did not
  complete a command"; the reader stayed attached throughout. A transfer
  had failed, most likely libaums' fixed 5-second transfer timeout while
  the card paused, and libaums sends the next command without a reset, so
  every retry met a reader still inside the old command. Now any failed
  transfer is followed by Reset Recovery (USB MSC BOT 1.0, 5.3.4: Bulk-Only
  Mass Storage Reset, then Clear Feature HALT on both endpoints through
  libaums' native clear-halt, which resets the host's data toggle too)
  before the command goes again, six times at most: about 30 seconds for a
  card that pauses, as Linux allows a command. A unit attention for a
  reset (ASC 29h) is retried; one for a changed card still stops the
  write. With v0.2.6 the owner's reader (MXTronics "MXT USB Device",
  `aaaa:8816`, an unregistered vendor ID) refused that reset, eleven times
  in a row, and four times the app closed: libaums 0.10.0's native
  clear-halt and port reset can never run, because its `libusb-lib.so`
  exports `Java_me_jahnen_libaums_usb_...` for a class in
  `me.jahnen.libaums.core.usb` (and `liberrno-lib.so` still exports the
  old `com.github.mjdev` names, so every errno reads 0). The driver no
  longer calls libaums' native code: it clears a halt with the standard
  GET_STATUS and CLEAR_FEATURE requests, only on an endpoint that reports
  one, and a reader that refuses the reset stops the write with that
  reason. The details name the first failed command, its block, how long
  it ran, and whether the phone still saw the reader afterwards. Not
  chosen: our own UsbCommunication with a longer timeout, which
  would need native code for clear-halt; libaums' UsbRequest mode, whose
  read path waits for the wrong request.
- Navigation effects run only once the back stack has an entry.

## Consequences

- Delete the file and use `BlockDeviceDriverFactory` when a libaums release
  passes `ScsiBlockDeviceTest`.
- The 16 KB page limitation in ADR 0014 is unchanged: a stalled transfer
  still calls libaums' native clear-halt.
