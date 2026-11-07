# 0014: Writing the card from the phone

Status: accepted, 2026-11-07

## Context

The owner wants setup without a computer and without weakening security.
ADR 0012 already made every card the same; what remains is writing it.
Browsers cannot write cards. Android can, through a USB card reader on the
phone's USB-C port, using the USB host API with a user-space mass-storage
driver (no root), as EtchDroid does.

## Decision

- The app offers "Write a card with this phone" next to DeKoi Setup. It
  writes the same Raspberry Pi OS image as DeKoi Setup, from the URL and
  SHA-256 values in `device/image.json` (generated into the app at build
  time, one source of truth), plus the same generic cloud-init files,
  rendered by code shared with DeKoi Setup in `clients/shared`.
- Libraries: libaums (Apache-2.0, user-space USB mass storage and FAT32)
  and XZ for Java (public domain, streaming xz decoding). Both pinned with
  Gradle dependency verification like every other dependency.
- Integrity: the download is streamed through SHA-256 and xz into the card;
  the compressed hash must match before the card is declared good, and the
  written blocks are read back and hashed against the extracted SHA-256.
  Any mismatch marks the card as failed and tells the owner to retry.
- Safety: the owner picks the reader explicitly (Android's USB permission
  prompt), sees its make and size, and confirms "Erase this card". The app
  writes only to USB mass-storage devices between 8 GB and 256 GB and never
  to the phone's own storage.

## Consequences

- No secret is on the card; linking still needs the power-on proof of ADR
  0012.
- The phone needs a USB-C card reader and about 3 GB of transfer; progress
  and resume are shown in plain words.
- Our code now includes a raw block writer, which ADR 0008 avoided on the
  desktop by using Imager. It is limited to removable USB mass storage
  behind the owner's explicit choice, and its verification is mandatory.
