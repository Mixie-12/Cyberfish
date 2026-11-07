# 0019: Watching the Wi-Fi for copies of the home network

Status: accepted, 2026-11-07

## Context

The owner wants DeKoi to notice Wi-Fi cloning: an "evil twin" access point
that copies the home network's name to lure devices onto it. A wired DeKoi
has an unused Wi-Fi radio on the Raspberry Pi 4 and 5. In managed mode it
can scan (names, BSSIDs, security, channel, signal); monitor mode is not
available on the stock Pi driver, so deauthentication floods are out of
reach.

## Decision

- A host-side systemd timer scans every five minutes with `iw` and writes
  the result as JSON to a read-only file the agent mounts, like the boot
  facts; the agent gets no new capability. The radio never joins a network
  it is not configured for, and never transmits beyond scanning.
- The home network's name comes from the owner in the app (stored on the
  device row, sent to the device in its RPC reply), or from the card when
  DeKoi itself uses Wi-Fi. The regulatory country comes from the same place
  the backend records the country (ADR 0015), else stays unset and the
  radio stays off.
- Learning: during the first 24 hours the agent records the access points
  (BSSID, security, channel) that carry the home name. After that, an
  access point with the home name that is open or weaker than the learned
  security is an `evil_twin` event, critical; a new BSSID with the same
  security is a warning, since mesh systems and new access points are
  common.
- Without a home network name the watch stays off and the app says why.

## Consequences

- Detects cloned names nearby, not deauthentication attacks or clones out
  of the DeKoi's range.
- Owner-supplied text (the Wi-Fi name) is bounded and stored as data; it is
  never put into a notification.
