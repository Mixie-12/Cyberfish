# Architecture

The decisions and their reasons are in `docs/PROMPT.md` section 3 and
`docs/decisions/`. This file describes how the running system fits together
and the contracts between its parts. The SQL in `backend/supabase/migrations/`
is the source of truth for every function named here.

## Data flow

```
Pi: OpenCanary --log file--> agent --HTTPS, anon key + device secret--> Supabase RPCs
                                                                            |
App <--HTTPS, user JWT, RLS-- Postgres tables <------------------------------+
App <--FCM push-- Edge Function "notify" <--database webhook on events insert
```

## Trust boundaries

| Boundary                        | Who is on the other side      | Control                                  |
| ------------------------------- | ----------------------------- | ---------------------------------------- |
| LAN to decoys                   | anyone on the customer LAN    | decoys in a locked-down container        |
| LAN discovery traffic to agent  | anyone on the customer LAN    | kernel filter, bounded parsers, cleaned and bounded strings, fixed vocabularies |
| Canary container to agent       | possibly compromised decoys   | one-way, read-only log volume; its image file read without following links, size bounded; no secrets |
| Agent to backend                | any internet client           | 256-bit device secret, input validation  |
| App to backend                  | any internet client           | Supabase Auth JWT, RLS, column grants    |
| Registry to device              | GHCR, CI                      | cosign signature required by Podman      |

## Pairing

1. DeKoi Setup generates a claim code: 128 random bits as 26 Crockford base32
   characters in canonical upper case. The QR payload is `DEKOI1:<code>`. The
   short name shown next to it is computed from the code by `clients/shared`.
2. The app calls `claim_device_v1(claim_code, name)`. This creates the device
   row owned by the user.
3. The agent generates a 256-bit secret (64 lower-case hex characters) on first
   boot and calls `device_register_v1(claim_code, secret, agent_version)` until
   the code has been claimed. It stores the returned device id with the secret.

The backend stores SHA-256 of the code and the secret in
`private.device_credentials`, a table no API role can reach. A code can be
claimed once and registered once.

## Device API

All functions are called by the agent through PostgREST
(`POST /rest/v1/rpc/<name>`) with the publishable key. They are an ABI:
a `_v1` function never changes; new behaviour goes into `_v2`.

| Function              | Arguments                                                   | Returns       |
| --------------------- | ----------------------------------------------------------- | ------------- |
| `device_register_v1`  | `claim_code`, `secret`, `agent_version`                     | device id     |
| `device_heartbeat_v1` | `device_id`, `secret`, `agent_version`, `services`, `local_ip` | nothing    |
| `device_heartbeat_v2` | the v1 arguments, `monitors` (up to 16), `images` (up to 8) | nothing      |
| `device_events_v1`    | `device_id`, `secret`, `events` (array, 1 to 200)           | rows inserted |
| `device_hosts_v1`     | `device_id`, `secret`, `hosts` (array, up to 1024)          | rows written  |
| `device_hosts_v2`     | `device_id`, `secret`, `hosts` (up to 1024), `networks` (up to 64) | rows written |

Event object: `agent_event_id` (device-unique integer), `occurred_at`,
`kind`, `service`, `src_ip`, `src_mac`, `dst_port`, `count`, `detail`.

Kinds a device may report (`private.device_event_kinds()`):

| Kind             | Source                                   | Typical detail                      |
| ---------------- | ---------------------------------------- | ----------------------------------- |
| `login_attempt`  | decoy                                    | `username`, `path`, `client_version` |
| `connection`     | decoy                                    | `path`, `user_agent`, `command`     |
| `port_scan`      | firewall log, closed ports on the box    | `ports`                             |
| `host_scan`      | ARP watcher, one host asking for many    | `targets`                           |
| `arp_spoof`      | ARP watcher, an address claimed twice    | `claimed_ip`, `previous_mac`        |
| `rogue_dhcp`     | DHCP probe                               | `server_ip`, `protocol`             |
| `rogue_router`   | IPv6 router advertisement watcher        | `protocol`                          |
| `name_poisoning` | LLMNR, NBT-NS and mDNS probes            | `protocol`                          |
| `ids_alert`      | Suricata                                 | `signature`, `category`, `severity`, `signature_id` |

`detail` accepts only these fields: strings up to 256 characters without
control characters; `targets`, `severity` and `signature_id` as non-negative
integers; `ports` as up to 64 numbers. Payload excerpts are never accepted.

Host object (v1): `mac`, `ip`, `vendor`. Version 2 adds the identification
of ADR 0010: `hostname` and `model` (1 to 64 characters, no control
characters), `kind` and `os` (the vocabularies in the ADR and in the
`network_hosts` check constraints) and `services` (up to 16 labels of
`[a-z0-9-]`, up to 32 characters). An identification field that a report
leaves out (absent or null, `kind` `"unknown"`, no `services`) keeps its
stored value, so a restarted agent does not erase what was learned.

Network object: `cidr`, `source` (`interface`, `router_advertisement`,
`dhcp_route` or `observed`) and `gateway`, the router serving the network
as seen from the device, or null. The agent reports each network once,
under its most direct source. A host or network repeated within one call
is refused as malformed. Hosts come from the bounded `arp-scan`; how the
agent identifies them and finds networks is in `device/agent/dekoi_agent/`
(`identify.py`, `classify.py`, `inventory.py`).

Monitoring report (ADR 0013). Monitor object: `name`, each once, from
`decoys`, `port_scan`, `arp_watch`, `dhcp_watch`, `router_watch`,
`name_poisoning`, `ids`, `inventory`; `state` from `ok`, `starting`, `off`,
`failed`; optional `detail` with only the counts `rules`, `hosts` and
`decoys` (up to 7 digits) and `ruleset_date` (`YYYY-MM-DD`). Image object:
`name`, each once, from `canary`, `agent`, `ids`; `version` (1 to 32
characters of `[0-9A-Za-z.+-]`); `updated_at`, the image's build time in UTC
(`YYYY-MM-DDTHH:MM:SSZ`). Both are stored in `devices.monitors` and
`devices.images`, checked by domains, and read by the owner; v1 leaves them
as they are. The agent calls v2 and falls back to v1 on 404 (an older
backend) or 400 (report refused), so the device stays online. How the agent
judges each monitor, and where the IDS and image facts come from, is in
`device/agent/dekoi_agent/monitors.py`.

Readiness: the agent tells systemd it is ready once the backend has
accepted its first heartbeat, or at once while it waits to be claimed. An
updated agent image that does not become ready within the unit's
`TimeoutStartSec` (15 minutes) is rolled back by `podman auto-update`. The
cost: when the backend is unreachable at boot, systemd restarts the agent
every 15 minutes until a heartbeat gets through (queued events are kept on
disk), and the IDS, ordered after the agent, starts after the first
timeout.

Responses the agent must handle:

| HTTP | Meaning                                  | Agent behaviour                             |
| ---- | ---------------------------------------- | ------------------------------------------- |
| 2xx  | accepted                                 | continue                                    |
| 400  | malformed input                          | drop the batch, log it; never retry it      |
| 403  | registration refused or secret rejected  | before registration: retry with backoff;    |
|      |                                          | after: device was removed, back off to 1 h  |
| 404  | function unknown (older backend)         | heartbeat: use `device_heartbeat_v1`        |
| 5xx, network error | backend unavailable            | keep the batch, retry with backoff          |

## App API (v1)

| Operation              | How                                                        |
| ---------------------- | ---------------------------------------------------------- |
| Claim a device         | `claim_device_v1(claim_code, name)`                        |
| Read own data          | `select` on `devices`, `events`, `network_hosts`, `networks` (RLS) |
| Rename, remove device  | `update` of `name`, `delete` on `devices` (RLS)            |
| Trust a host           | `update` of `trusted` on `network_hosts` (RLS)             |
| Name a host, its room  | `update` of `label` (1 to 40 characters) and `room` (1 to 32), no control characters, on `network_hosts` (RLS) |
| Mark events seen       | `acknowledge_events_v1(event_ids)`: up to 500 ids, own devices' events only; `events.seen_at` keeps the first time; returns the number newly marked |
| Push token             | `register_push_token_v1(token)`, `unregister_push_token_v1(token)` |
| Delete account         | `delete_account_v1()`                                      |

## Event severity

`events.severity` is generated from the kind, and for `ids_alert` from the
Suricata severity, by `private.event_severity` (migration
`20261107160000_device_identification.sql`, which documents the mapping):
`critical`, `warning` or `info`. Clients read the column and keep no
mapping of their own.

## Scheduled jobs

pg_cron runs `private.mark_offline_devices()` every 5 minutes (one
`device_offline` event per outage) and `private.apply_retention()` nightly
(events, hosts and networks older than 90 days).

## Notifications

Every insert into `events` posts `{"event_id": <id>}` with the header
`X-Notify-Secret` to the Edge Function `notify` (pg_net trigger; URL and
secret in Vault). `notify` asks `notification_targets_v1(event_id)` which
phones to notify and with which template, and sends FCM HTTP v1 messages
whose data payload is `event_id` only. The rules are in that function
(ADR 0007).
