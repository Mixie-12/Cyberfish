# Architecture

The decisions and their reasons are in `docs/PROMPT.md` section 3 and
`docs/decisions/`. This file describes how the running system fits together
and the contracts between its parts. The SQL in `backend/supabase/migrations/`
is the source of truth for every function named here.

## Data flow

```
Pi: OpenCanary --log file--> agent --HTTPS, anon key + device secret--> Supabase RPCs
Pi: host Wi-Fi scan (iw) --/run/dekoi/wifi--> agent --country--> host Wi-Fi scan
                                                                            |
App <--HTTPS, user JWT, RLS-- Postgres tables <------------------------------+
App <--FCM push-- Edge Function "notify" <--database webhook on events insert

agent --ICMP, UDP 53, HTTP (UPnP, read-only)--> gateway and resolver on the LAN
agent --HTTPS--> 1.1.1.1 (DNS over HTTPS), speed.cloudflare.com (speed test, opt-in)
```

## Trust boundaries

| Boundary                        | Who is on the other side      | Control                                  |
| ------------------------------- | ----------------------------- | ---------------------------------------- |
| LAN to decoys                   | anyone on the customer LAN    | decoys in a locked-down container        |
| LAN discovery traffic to agent  | anyone on the customer LAN    | kernel filter, bounded parsers, cleaned and bounded strings, fixed vocabularies |
| Canary container to agent       | possibly compromised decoys   | one-way, read-only log file; no secrets  |
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
| `login_attempt`  | decoy                                    | `username`, `path`, `client_version` |
| `connection`     | decoy                                    | `path`, `user_agent`, `command`     |
| `port_scan`      | firewall log, closed ports on the box    | `ports`                             |
| `host_scan`      | ARP watcher, one host asking for many    | `targets`                           |
| `arp_spoof`      | ARP watcher, an address claimed twice    | `claimed_ip`, `previous_mac`        |
| `rogue_dhcp`     | DHCP probe                               | `server_ip`, `protocol`             |
| `rogue_router`   | IPv6 router advertisement watcher        | `protocol`                          |
| `name_poisoning` | LLMNR, NBT-NS and mDNS probes            | `protocol`                          |
| `ids_alert`      | Suricata                                 | `signature`, `category`, `severity`, `signature_id` |
| `evil_twin`      | Wi-Fi watch, a weaker copy of the home network | `security`, `home_security`, `channel`, `signal_dbm` |
| `new_access_point` | Wi-Fi watch, a new access point with the home name | as `evil_twin` |

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
`signal_dbm` as 0 or a negative integer of up to three digits. Payload
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
`cf-connecting-ip`, or else the first `X-Forwarded-For` entry, the header
Supabase documents for the client address, and the country from
`cf-ipcountry` when present; the device never reports either. The reply's
`speed_test` is true while the owner has the test on and no result is stored
for 20 hours.

Responses the agent must handle:

| HTTP | Meaning                                  | Agent behaviour                             |
| ---- | ---------------------------------------- | ------------------------------------------- |
| 2xx  | accepted                                 | continue                                    |
| 400  | malformed input                          | drop the batch, log it; never retry it      |
| 403  | registration refused or secret rejected  | before registration: retry with backoff;    |
|      |                                          | after: device was removed, back off to 1 h  |
| 5xx, network error | backend unavailable            | keep the batch, retry with backoff          |

## App API (v1)

| Operation              | How                                                        |
| ---------------------- | ---------------------------------------------------------- |
| Claim a device         | `claim_device_v1(claim_code, name)`                        |
| Read own data          | `select` on `devices`, `events`, `network_hosts`, `networks`, `health_samples`, `port_forwards`, `speed_tests`, `owner_settings` (RLS) |
| Rename, remove device  | `update` of `name`, `delete` on `devices` (RLS)            |
| Speed test on or off   | `update` of `speed_test` on `devices` (RLS)                |
| Set the Wi-Fi name     | `update` of `wifi_name` on `devices` (RLS), null for none  |
| Trust a host           | `update` of `trusted` on `network_hosts` (RLS)             |
| Weekly summary on, off | `select`, and `insert` or `update` of `weekly_summary`, on `owner_settings` (RLS); `user_id` defaults to the caller; no row means on |
| Push token             | `register_push_token_v1(token)`, `unregister_push_token_v1(token)` |
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
older than 90 days) and `private.post_weekly_summaries()` on Sundays at
17:00 UTC.

## Notifications

Every insert into `events` posts `{"event_id": <id>}` with the header
`X-Notify-Secret` to the Edge Function `notify` (pg_net trigger; URL and
secret in Vault). `notify` asks `notification_targets_v2(event_id)` which
phones to notify, with which template, severity and source kind, and sends
FCM HTTP v1 messages whose data payload is `event_id` only. The rules are in
`notification_targets_v1` (ADR 0007); `new_host` events notify at most once
per DeKoi per 10 minutes, so devices that join together give one push.

The weekly job posts `{"weekly_summary_for": "<user id>"}` the same way, once
per owner with a registered DeKoi. `notify` asks `weekly_summary_v1(user_id)`,
which returns the owner's phones with three numbers for the last 7 days
(alerts as the app groups them into incidents, new devices, uptime in whole
percent), or nothing when the owner turned the summary off or already had
one in the last 6 days. The push uses a fixed template on the `updates`
channel and carries no data.
