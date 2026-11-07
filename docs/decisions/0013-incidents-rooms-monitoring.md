# 0013: Incidents, device names and rooms, monitoring status

Status: accepted, 2026-11-07

## Context

On the phone, the alert list became a cluster of similar rows: one scan from
one device produced many events. The owner wants a calm timeline of
incidents that can be marked as read, to name devices and say where they are,
and to see what DeKoi is watching and when it last updated.

## Decision

- Incidents are grouped in the app, not stored: events from the same DeKoi
  and the same source (source MAC, else source IP; DeKoi itself for
  `device_offline` and `device_online`), each within 30 minutes of the
  previous one, form one incident. Its severity is the highest of its
  events. The timeline shows incidents; the events are its details.
- Read state is stored: `events.seen_at` (null until seen) and
  `acknowledge_events_v1(event_ids bigint[])` for signed-in owners, at most
  500 ids, only their own devices' events. Opening an incident marks all its
  events seen; seen incidents are shown grey. The Alerts badge counts unseen
  critical and warning incidents not from known devices.
- Names and rooms: `network_hosts.label` (the owner's name, up to 40
  characters) and `network_hosts.room` (up to 32), both without control
  characters, writable by the owner through column grants and RLS like
  `trusted`. The display name is label, then host name, then model, then
  maker and kind. Rooms group devices in the app ("Living room TV").
- Monitoring status: `device_heartbeat_v2(device_id, secret, agent_version,
  services, local_ip, monitors, images)`. `monitors` is up to 16 of `{name,
  state, detail}`: name from `decoys`, `port_scan`, `arp_watch`,
  `dhcp_watch`, `router_watch`, `name_poisoning`, `ids`, `inventory`; state
  from `ok`, `starting`, `off`, `failed`; detail holds only small numbers
  and dates (`rules`, `ruleset_date`, `hosts`). `images` is up to 8 of
  `{name, version, updated_at}`, name from `canary`, `agent`, `ids`. Stored
  on `devices`, validated by the database, read by the owner. v1 keeps
  working.

## Consequences

- Grouping rules live in one tested Kotlin function; the notify function's
  10-minute coalescing per source (ADR 0007) already sends one push per
  incident in practice.
- Labels and rooms are owner text: shown as plain text, never sent to a
  notification (abuse case 8).
- Monitor states come from the device; a compromised device can only lie
  about itself.
