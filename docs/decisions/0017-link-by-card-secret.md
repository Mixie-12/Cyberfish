# 0017: Link by a secret the phone writes onto the card

Status: accepted, 2026-11-07. Supersedes 0012.

## Context

ADR 0012 linked a generic card by having the owner restart the DeKoi. A
security review found that anyone on the LAN can enroll a fake device,
advertise it and report a fresh boot whenever the owner asks to link: the
owner would link the fake one and believe the network is protected. A
restart cannot be proven by the device being proven. Linking needs a
secret that reaches the real device through a physical path.

## Decision

- The phone writes the card (ADR 0014). Before writing, the app generates a
  claim code (`ClaimCode.generate`, 128 bits), renders it into the card's
  cloud-init files with the shared renderer, writes and verifies the card,
  and then claims the code with `claim_device_v1`. On first start the
  device registers with the code through `device_register_v1`. This is the
  existing, tested claim protocol; only the writer changes.
- Cards written by DeKoi Setup keep the code too, and DeKoi Setup keeps
  showing it as a QR code and as text for the app to scan or type.
- No generic enrollment, no LAN advertisement, no restart proof: nothing a
  device on the LAN can imitate.

## Consequences

- The code is on the card in plain text, as with DeKoi Setup cards: someone
  who takes the card before its first start could register it first. The
  owner would see a device that never comes online; writing a new card
  fixes it. Physical theft of the card is out of scope (docs/security.md).
- Setup without a computer needs a USB-C card reader for the phone; without
  one, DeKoi Setup and the QR code remain.
- ADR 0012's RPCs and agent changes are not built.
