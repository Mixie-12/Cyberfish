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
  classes, FAT32) stays libaums 0.10.0. Changed beyond upstream: a retried
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
  under the tag `DeKoiCard`. The write stops with the partition table
  zeroed, as before.
- Navigation effects run only once the back stack has an entry.

## Consequences

- Delete the file and use `BlockDeviceDriverFactory` when a libaums release
  passes `ScsiBlockDeviceTest`.
- The 16 KB page limitation in ADR 0014 is unchanged: a stalled transfer
  still calls libaums' native clear-halt.
