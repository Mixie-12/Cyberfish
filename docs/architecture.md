# Architecture

The decisions and their reasons are in `docs/PROMPT.md` section 3 and
`docs/decisions/`. This file describes how the running system fits together
and the contracts between its parts. The SQL in `backend/supabase/migrations/`
is the source of truth for every function named here.

## Data flow

```
Pi: OpenCanary, Samba --log files--> agent --HTTPS, anon key + device secret--> Supabase RPCs
Pi: host Wi-Fi scan (iw) --/run/dekoi/wifi--> agent --country--> host Wi-Fi scan
                                                                            |
App <--HTTPS, user JWT, RLS-- Postgres tables <------------------------------+
App <--FCM push-- Edge Function "notify" <--database webhook on events insert

agent --ICMP, UDP 53, HTTP (UPnP, read-only)--> gateway and resolver on the LAN
agent --HTTPS--> 1.1.1.1 (DNS over HTTPS), speed.cloudflare.com (speed test, opt-in)
```

## Decoys

One image, `dekoi-canary`, runs as two containers, both with their own user
namespace, no capabilities and a read-only root (ADR 0005, ADR 0018):

| Container        | Runs                                  | Volumes                          |
| ---------------- | ------------------------------------- | -------------------------------- |
| `dekoi-canary`   | OpenCanary and the Samba file share   | log volume (`opencanary.log`, `samba.log`), SSH host keys |
| `dekoi-announce` | NetBIOS names (`nmbd`), mDNS (`avahi-daemon`), WS-Discovery (`wsdd2`) | none |

Their ports are the sets in `device/host/etc/nftables.conf`, which a test
keeps equal to the decoys. The persona (ADR 0035: name, company,
workgroup, shares, bait files and the user names in them) is defined once
in `device/canary/persona/` and filled into the configuration, OpenCanary's
included, when the image is built; new cards take its name as host name,
and no decoy uses the host name, so cards with an older one show the same
persona. Discovery (NetBIOS, mDNS,
WS-Discovery, listing the shares through `IPC$`, anonymous probes) never
becomes an event; signing in, opening a share and reading a file do.

## Trust boundaries

| Boundary                        | Who is on the other side      | Control                                  |
| ------------------------------- | ----------------------------- | ---------------------------------------- |
| LAN to decoys                   | anyone on the customer LAN    | decoys and their announcements in locked-down containers |
| LAN discovery traffic to agent  | anyone on the customer LAN    | kernel filter, bounded parsers, cleaned and bounded strings, fixed vocabularies |
| Canary container to agent       | possibly compromised decoys   | one-way, read-only log volume; its image file read without following links, size bounded; no secrets |
| Wi-Fi radio to agent            | anyone within radio range     | the host only scans; iw's text read-only, parsed unprivileged, bounded, cleaned |
| Agent to host Wi-Fi scan        | a compromised agent           | one file, three bytes read, two capital letters accepted |
| Agent to backend                | any internet client           | 256-bit device secret, input validation  |
| Gateway and resolver to agent   | a possibly compromised router | read-only requests to the gateway's own address, bounded size and time, no document types, values validated before they are reported |
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
| `device_health_v1`    | `device_id`, `secret`, `samples` (up to 216), `dns`, `resolver`, `forwards` (up to 64, or null), `download_mbps` | `{"speed_test": bool}` |
| `device_wifi_v1`      | `device_id`, `secret`, `watch`                              | `{name, country}` |

Event object: `agent_event_id` (device-unique integer), `occurred_at`,
`kind`, `service`, `src_ip`, `src_mac`, `dst_port`, `count`, `detail`.

Kinds a device may report (`private.device_event_kinds()`):

| Kind             | Source                                   | Typical detail                      |
| ---------------- | ---------------------------------------- | ----------------------------------- |
| `login_attempt`  | decoy                                    | `username`, `path`, `client_version`, `bait` (a user name from the bait files, ADR 0024) |
| `connection`     | decoy                                    | `path`, `user_agent`, `command`, `share` |
| `port_scan`      | firewall log, closed ports on the box    | `ports`                             |
| `host_scan`      | ARP watcher, one host asking for many    | `targets`                           |
| `arp_spoof`      | ARP watcher, an address claimed twice    | `claimed_ip`, `previous_mac`        |
| `rogue_dhcp`     | DHCP probe                               | `server_ip`, `protocol`             |
| `rogue_router`   | IPv6 router advertisement watcher        | `protocol`                          |
| `name_poisoning` | LLMNR, NBT-NS and mDNS probes            | `protocol`                          |
| `ids_alert`      | Suricata                                 | `signature`, `category`, `severity`, `signature_id` |
| `evil_twin`      | Wi-Fi watch, a weaker copy of the home network | `security`, `home_security`, `channel`, `signal_dbm` |
| `new_access_point` | Wi-Fi watch, a new access point with the home name | as `evil_twin` |
| `file_access`    | file share decoy, a file was read        | `share`, `file`                     |

The backend writes five more kinds itself (`private.event_kinds()`):
`device_offline` and `device_online` (scheduled job and heartbeat),
`dns_hijack` and `port_forward` (network health, below), and `new_host`.
Once a DeKoi's first inventory row is 24 hours old, a trigger on
`network_hosts` turns the first sighting of a hardware address into a
`new_host` event carrying its `src_mac` and `src_ip` (ADR 0015). The
learning period is per DeKoi; an address another DeKoi knows is still new.

`detail` accepts only these fields: strings up to 256 characters without
control characters; `targets`, `severity` and `signature_id` as non-negative
integers; `ports` as up to 64 numbers; `security` and `home_security` as one of
`open`, `wep`, `wpa`, `wpa2`, `wpa3`; `channel` as up to three digits;
`signal_dbm` as 0 or a negative integer of up to three digits; `bait` as a
boolean. Payload
excerpts and network names are never accepted. For the Wi-Fi watch kinds,
`src_mac` is the access point's BSSID.

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
## Network health (ADR 0015)

Every minute the agent sends one ICMP echo to the default gateway, looks up
the backend's name at the first IPv4 name server in `/etc/resolv.conf`, and
opens a TCP connection to the backend at the address that lookup gave
(`device/agent/dekoi_agent/health.py`). Once an hour it calls
`device_health_v1` with:

- `samples`: one object per finished hour and target (`gateway`,
  `internet`, `resolver`): `hour`, `target`, `probes`, `lost`, `median_ms`,
  `worst_ms` (null when every probe was lost) and `outage_minutes` (failed
  probes next to another failed one). Unreported hours wait in memory for
  up to 72 hours; a resent hour replaces the stored one.
- `dns` and `resolver`: the DNS check (`dns_check.py`). Three names with
  long-stable anycast addresses, A and AAAA, are resolved by the LAN
  resolver and by DNS over HTTPS at `https://1.1.1.1/dns-query`. Answers
  that point nowhere (none, `0.0.0.0`, `::`) do not count; two or more names
  with disjoint answers is a mismatch. The agent reports `mismatch` only
  after two checks in a row, `ok` or `unavailable` otherwise, null while a
  mismatch is unconfirmed.
- `forwards`: the gateway's enabled UPnP IGD port mappings (`upnp.py`):
  `protocol`, `external_port`, `internal_client`, `internal_port`,
  `description`, or null when they could not be read. The description URL
  comes from the gateway's SSDP answers, which the discovery listener
  already receives; the agent sends only `GetGenericPortMappingEntry`.
- `download_mbps`: the speed test, run only when the previous reply said
  `speed_test: true` and between 02:00 and 05:00 UTC: one download of at most
  25 MB and 30 s from `https://speed.cloudflare.com/__down`.

The backend decides what is new. A change to `mismatch` writes a
`dns_hijack` event (critical, without a source MAC) and a mapping never seen
before writes a `port_forward` event (warning) whose `src_mac` is the
inventory's host at that address. It records the public address from
`private.client_ip()` (the platform's `cf-connecting-ip`, ADR 0016) and the
country from `cf-ipcountry` when present; the device never reports either. The reply's
`speed_test` is true while the owner has the test on and no result is stored
for 20 hours.

Responses the agent must handle:

| HTTP | Meaning                                  | Agent behaviour                             |
| ---- | ---------------------------------------- | ------------------------------------------- |
| 2xx  | accepted                                 | continue                                    |
| 400  | malformed input                          | drop the batch, log it; never retry it      |
| 403  | registration refused or secret rejected  | before registration: retry with backoff;    |
|      |                                          | after: device was removed, back off to 1 h  |
| 404  | function unknown (older backend)         | heartbeat: use `device_heartbeat_v1`        |
| 429  | over a request or storage limit          | keep the batch, retry with backoff          |
| 5xx, network error | backend unavailable            | keep the batch, retry with backoff          |

Every write through the Data API passes `guard.check_request()` first,
which counts it per client address and per user (ADR 0016, limits in
`private.request_limits()`). Triggers bound what one account or device can
store (migration `20261108000000_request_and_storage_limits.sql`).

## App API (v1)

| Operation              | How                                                        |
| ---------------------- | ---------------------------------------------------------- |
| Claim a device         | `claim_device_v1(claim_code, name)`                        |
| Read own data          | `select` on `devices`, `events`, `network_hosts`, `networks`, `health_samples`, `port_forwards`, `speed_tests`, `owner_settings` (RLS) |
| Rename, remove device  | `update` of `name`, `delete` on `devices` (RLS)            |
| Speed test on or off   | `update` of `speed_test` on `devices` (RLS)                |
| Set the Wi-Fi name     | `update` of `wifi_name` on `devices` (RLS), null for none  |
| Trust a host           | `update` of `trusted` on `network_hosts` (RLS)             |
| Name a host, its room  | `update` of `label` (1 to 40 characters) and `room` (1 to 32), no control characters, on `network_hosts` (RLS) |
| Mark events seen       | `acknowledge_events_v1(event_ids)`: up to 500 ids, own devices' events only; `events.seen_at` keeps the first time; returns the number newly marked |
| Weekly summary on, off | `select`, and `insert` or `update` of `weekly_summary`, on `owner_settings` (RLS); `user_id` defaults to the caller; no row means on |
| Push token             | `register_push_token_v2(token, language)` (`en` or `nb`, anything else stored as `en`; v1 stores `en`), `unregister_push_token_v1(token)` |
| Delete account         | `delete_account_v1()`                                      |

## Wi-Fi watch

ADR 0019. The owner gives the home network's name (`devices.wifi_name`, up
to 32 bytes). Every five minutes the agent calls `device_wifi_v1` with the
watch's state and gets `{name, country}`; the country comes from the
`cf-ipcountry` header the platform's edge sets on the device's request, and
is null when absent. With both, the agent writes the country to
`/run/dekoi/wifi-country/country`; without either, it removes it.

`dekoi-wifi-scan.timer` runs `/usr/local/libexec/dekoi-wifi-scan` (host
files in `device/host/`) every five minutes. It acts only while the country
file holds two capital letters, sets the country with
`raspi-config nonint do_wifi_country` when it changes, scans with
`iw dev wlan0 scan` only while another interface has the default route and
wlan0 is not connected, and replaces `/run/dekoi/wifi/scan` with a status
line (`scan`, `uplink`, `no_radio`) and up to 1 MiB of iw's output. The
agent parses it (`device/agent/dekoi_agent/wifi.py`), learns the access
points with the home name for 24 hours, then reports `evil_twin` and
`new_access_point`.

`devices.wifi_watch` is the state the device last reported: `no_name`,
`no_country`, `no_radio` (no shared directories on an older card, no wlan0,
or no scan for 15 minutes), `uplink`, `learning`, `watching`.

## Event severity

`events.severity` is generated from the kind, and for `ids_alert` from the
Suricata severity, by `private.event_severity`: `critical`, `warning` or
`info`. The pgTAP test `12_event_vocabulary` lists every kind with its
severity and fails when a kind has none. Clients read the column and keep
no mapping of their own.

## Scheduled jobs

pg_cron runs `private.mark_offline_devices()` every 5 minutes (one
`device_offline` event per outage), `private.apply_retention()` nightly
(events, hosts, networks, health samples, port forwards and speed tests
older than 90 days; pg_cron's own run history older than 30 days), `private.expire_pending_setups()` nightly (setups
whose DeKoi never registered within 30 days),
`private.prune_rate_counters()` every 10 minutes (ended request windows)
and `private.post_weekly_summaries()` on Sundays at 17:00 UTC.

## Notifications

Every insert into `events` posts `{"event_id": <id>}` with the header
`X-Notify-Secret` to the Edge Function `notify` (pg_net trigger; URL and
secret in Vault). `notify` asks `notification_targets_v3(event_id)` which
phones to notify, with which template, severity, source kind and language
(ADR 0025), and sends FCM HTTP v1 messages whose data payload is `event_id`
only. The rules are in `notification_targets_v1` (ADR 0007); `new_host`
events notify at most once per DeKoi per 10 minutes, so devices that join
together give one push. The webhook fires only while the account and the
project are under their hourly notify limits (ADR 0016).

The weekly job posts `{"weekly_summary_for": "<user id>"}` the same way, once
per owner with a registered DeKoi. `notify` asks `weekly_summary_v2(user_id)`,
which returns the owner's phones and their languages with three numbers
for the last 7 days (alerts as the app groups them into incidents, new
devices, uptime in whole percent), or nothing when the owner turned the
summary off or already had one in the last 6 days. The push uses a fixed
template on the `updates` channel and carries no data.

`notify` accepts the secret in `NOTIFY_SECRET` and, while it is rotated,
the one in `NOTIFY_SECRET_PREVIOUS` (`docs/runbooks/rotate-keys.md`).
