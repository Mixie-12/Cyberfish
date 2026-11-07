# 0012: Linking a DeKoi from the phone

Status: accepted, 2026-11-07

## Context

Today DeKoi Setup writes a claim code onto the card and shows it as a QR
code; the app scans it. The owner wants setup to be easy for anyone and done
on the phone, without the QR step. Browsers cannot write memory cards, and a
phone can only do it through a USB card reader with our own raw disk writer
(roadmap). The card still has to be written once, by DeKoi Setup or in the
factory.

## Decision

- The card carries no account data and no code: every card is the same
  (Wi-Fi settings stay optional). Cards with a claim code from earlier
  versions keep working through `device_register_v1` and `claim_device_v1`.
- First start without a claim code: the agent calls
  `device_enroll_v1(agent_version)` and gets a device id and a 256-bit
  secret; the device has no owner. Unowned devices that are never linked
  are deleted after 24 hours.
- While it has no owner, the agent advertises `_dekoi._tcp` over mDNS on its
  own segment, TXT `id=<device id>`, instance name "DeKoi XXXX" (the
  existing short-name rule). Nothing secret is advertised; the
  advertisement stops once the device is linked.
- The app finds the service with Android's `NsdManager`, the owner taps
  Link, and the app calls `request_link_v1(device_id)`: a pending request
  for that user, valid 10 minutes, at most one per user, only for unowned
  devices.
- Proof of physical access: the app asks the owner to unplug the DeKoi's
  power and plug it back in. On every start the agent calls
  `device_confirm_link_v1(device_id, secret, booted_at)`. It links the
  device when exactly one request was made before this boot, the boot is at
  most 5 minutes old and the request at most 10 minutes; with two or more
  requests nobody is linked (fail closed) and the requests are dropped. It
  returns `linked`, `none` or `conflict`.
- The app polls the device list and shows each step: found, waiting for the
  restart, linked, online, watching.

## Consequences

- Nobody on the network can link a DeKoi without restarting it by hand. A
  power cut during someone else's 10-minute window would link it to them;
  the window is short and the device must be unowned.
- A second app asking at the same time blocks linking until it stops; the
  app tells the owner to restart and try again.
- During setup an attacker on the LAN can see that a DeKoi is present; once
  linked it advertises nothing and looks like a storage box again.
- The phone must be on the same network as the DeKoi during setup.
- DeKoi Setup loses its QR screen; the app keeps "Type the code" for old
  cards.
