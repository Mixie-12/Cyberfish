# 0010: Device identification, networks and severity

Status: accepted, 2026-11-07

## Context

The owner wants DeKoi to say what every device on the network is (a TV, a
phone, a switch), list every network it can see, and explain alerts so a
non-technical person knows how serious they are and what to do. Today a host
is a MAC address, an IP address and an OUI vendor from `arp-scan`; events have
no severity. This is beyond `docs/PROMPT.md` and was asked for by the owner
after the Phase 7 hardware test.

## Decision

Identification stays on the device and is passive or uses the standard
discovery that any storage box sends, so DeKoi still looks like one:

- Listen (AF_PACKET, already used for ARP and DHCP): DHCP requests (host
  name, vendor class, parameter request list), mDNS announcements and
  answers, SSDP NOTIFY, NetBIOS name broadcasts, LLDP frames, IPv6 router
  advertisement prefixes.
- Ask, once per inventory cycle: one mDNS service enumeration and one SSDP
  M-SEARCH, both multicast on the local segment. No per-host probing beyond
  the existing bounded `arp-scan`.
- Classify with one rules table in the agent (OUI vendor, mDNS service types,
  SSDP device types, DHCP vendor class and fingerprint, host name patterns).
  Unknown stays `unknown`; nothing is guessed from a single weak signal.

Contract (additive; `device_hosts_v1` keeps working until no device runs it):

- `device_hosts_v2(device_id, secret, hosts, networks)`. A host adds
  `hostname`, `model` (bounded text, no control characters), `kind`, `os`
  (fixed vocabularies below) and `services` (up to 16 labels of `[a-z0-9-]`,
  up to 32 characters). `networks` is up to 64 of `{cidr, source, gateway}`,
  `source` one of `interface`, `router_advertisement`, `dhcp_route`,
  `observed`.
- `kind`: `router`, `switch`, `access_point`, `computer`, `phone`, `tablet`,
  `tv`, `media_player`, `speaker`, `game_console`, `printer`, `camera`,
  `storage`, `smart_home`, `wearable`, `server`, `unknown`.
- `os`: `android`, `ios`, `ipados`, `macos`, `windows`, `linux`, `chromeos`,
  `tvos`, `webos`, `tizen`, `fire_os`, `other`.
- `public.networks` (owner-readable through RLS like `network_hosts`).
- `events.severity`, generated from the event kind (and the IDS severity for
  `ids_alert`): `critical`, `warning` or `info`. The app and the `notify`
  function both read this column; neither keeps its own mapping.

Notifications may name the source device's `kind` and the event `severity`:
both come from fixed vocabularies checked by the database, so no
attacker-written text reaches a notification (abuse case 8 holds). Host
names, models and service labels are shown in the app as plain text only.

## Consequences

- Host names and models are attacker-controlled; they are bounded and
  stripped like every other device string and never leave the app.
- Classification is heuristic. The app says "looks like a TV" for inferred
  kinds and lets the owner correct it later (roadmap).
- Subnets behind routers are listed only when DeKoi sees evidence of them
  (routes, prefixes, traffic); DeKoi does not scan beyond its own segment.
