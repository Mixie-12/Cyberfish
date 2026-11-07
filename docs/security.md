# Security

How DeKoi is protected, and the test or evidence behind each claim. Status
words: **tested** (automated, runs in CI), **verified** (checked by hand,
date recorded in `docs/STATUS.md`), **pending** (not yet shown).

## Assets

- Owners' data: events, the inventory of their home network, their account.
- Control of the fleet: whoever can make devices run an image controls a
  device inside every customer's network.
- Device credentials (256-bit secrets) and pairing codes.
- Credentials of the delivery chain: the image signing keys (Cloud KMS, an
  offline backup, the first cosign key until it retires), the Android
  signing key, the Supabase access token, the notify webhook secret and FCM
  key. Where each lives and how it is replaced: `docs/runbooks/rotate-keys.md`.

## Actors and trust boundaries

| Actor                         | Can reach                                  | Must never reach                         |
| ----------------------------- | ------------------------------------------ | ---------------------------------------- |
| Anyone on the customer LAN    | decoys and their announcements, the firewall, broadcast traffic | the agent, its secret, the host, any real file |
| A compromised router          | answers to the agent's own requests        | the agent's secret, any change on the router made by DeKoi |
| Anyone within Wi-Fi range     | the scan results (what their beacons say)  | the agent's privileges, the host, notification text |
| A compromised decoy process   | its own container, write-only log volume   | secrets, the backend, the host           |
| A compromised announcer       | its own container, nothing else            | any volume, events, secrets, the host    |
| Any internet client           | the Data API with the publishable key      | any row, any function except device RPCs |
| A signed-in owner             | their own rows through RLS                 | other owners' rows, device credentials   |
| A stolen or cloned device     | its own device's rows through device RPCs  | other devices' rows, owners' data        |
| Someone with GHCR push access | the registry                               | devices (signature required)             |
| A workflow on a branch or pull request | a read-only token                 | any secret, the signing key, production  |
| Someone with write access to the repository | branches, tags, workflows    | signing without the owner's approval     |

The data flow and every contract are in `docs/architecture.md`.

## Abuse cases (brief, section 4)

| #  | Abuse case                                                       | Control                                                     | Evidence |
| -- | ---------------------------------------------------------------- | ----------------------------------------------------------- | -------- |
| 1  | User A reads or changes user B's data                            | RLS on every table, column grants, ownership checks in RPCs | tested: `backend/supabase/tests/database/03_isolation.test.sql`, `00_hardening`, `08_device_identification`, `10_new_host`, `11_weekly_summary` |
| 2  | Anonymous caller reads any table                                 | default privileges revoked, explicit grants only            | tested: `00_hardening`, `03_isolation`, `08_device_identification`, `11_weekly_summary` |
| 3  | Claim code guessing, reuse, probing, flooding                    | 128-bit codes, hashed, single use per side, generic errors, 5 pending per user | tested: `01_claim_register` |
| 4  | Device impersonation, writing another device's rows              | 256-bit secret per device, hashed, rows scoped to the secret's device | tested: `02_device_api`, `08_device_identification` |
| 5  | Oversized or malformed input, control characters, HTML           | size and shape checks in RPCs, field allowlist, bounded strings without control characters, fixed vocabularies; on the device, a kernel filter and bounded parsers for discovery traffic | tested: `02_device_api`, `06_network_detection`, `08_device_identification`, agent `test_sources`, `test_identify` (including a seeded fuzz test) |
| 6  | Code execution inside the canary or the announcer (OpenCanary, Samba, avahi, wsdd2) | own user namespace, no capabilities, read-only root, no secrets; the canary writes only its log volume, the announcer mounts nothing | tested (lockdown starts under real Podman): `device/e2e/run.py`; config review: `dekoi-canary.container`, `dekoi-announce.container` |
| 7  | Unsigned, foreign or edge image pushed to the registry           | Podman `policy.json`: only the three release repositories, signed with a key on the card; edge images unsigned in separate repositories | tested: `device/tests/signatures.sh` with the image library of Debian trixie, the card's release (`signatures` job), `test_host_config` |
| 8  | Attacker-controlled strings in notifications                     | fixed templates by event kind; payload carries the event id only; the weekly summary is a fixed template with three whole numbers and no payload | tested with Phase 5 (`notify` function tests) |
| 9  | Anything beyond the intended surface on the project URL          | public schema only, GraphQL dropped, no buckets, no publications | verified 2026-11-07 against production: anonymous reads 401, private schema and GraphQL not exposed, no buckets, notify refuses requests without the secret |
| 10 | Secrets in git history                                           | secrets only in GitHub environments, Cloud KMS and 1Password; push protection | tested: gitleaks over the whole history on every push (`repo.yml`) |
| 11 | A hostile router or resolver feeds the agent crafted UPnP or DNS answers | requests to the gateway's own address only, read-only UPnP action, bodies bounded in size and time, XML parsed as UTF-8 and refused with a document type (no entity expansion), every value validated; DNS replies matched to the query and parsed by the bounded discovery parser | tested: agent `test_upnp`, `test_dns_check`, `test_health` |
| 12 | A device reports forged network health                           | secret-checked RPC, bounded arrays, repeated keys refused, table constraints; the public address is taken from the request, never from the device | tested: `10_network_health` |
| 13 | Forged access points: crafted names and elements, beacon floods  | the host only scans; iw's text parsed by the unprivileged agent, split on newline only, first kernel field per block, privacy bit decides open, names decoded, bounded to 32 bytes and cleaned; at most 16 reports a day | tested: agent `test_wifi` with real iw output |
| 14 | The owner's Wi-Fi name as an injection vector                    | column grant, 32 bytes, no control characters, never in a notification or on the host | tested: `13_wifi_watch`, `00_hardening`, `notify` tests |
| 15 | A compromised agent misusing the radio                           | the host reads three bytes of one file and accepts two capital letters; raspi-config checks the ISO list; the scan unit keeps only CAP_NET_ADMIN on a read-only system; the agent can make the host scan or stop, nothing else | config review; shellcheck and `systemd-analyze verify` in CI; pending on hardware |
| 16 | Flooding the API from one address or account                     | write limits per address and per user in PostgREST's pre-request function (ADR 0016) | tested: `14_request_limits`; HTTP 429 through the local gateway verified 2026-11-08 |
| 17 | Filling the database or the function quota from accounts and devices of one's own | storage limits per account and device, push tokens capped, abandoned setups removed, notify calls capped per account and in all (ADR 0016) | tested: `15_storage_limits` |
| 18 | Signing or deploying code nobody approved                        | `permissions: {}` in every workflow; signing only in the `release` environment (v* tags, owner approval), bound by workload identity federation to that workflow, ref and environment; production secrets only on `main` | config: `infra/github/apply.sh`, `infra/google/github.tf`; pending: repository public and `apply.sh` run |
| 19 | A leaked CI credential stays useful                              | KMS signing key cannot be exported; webhook secret and FCM key replaced monthly and never in GitHub; no database password in CI; an issue 30 days before the other credentials expire | `rotate-keys.yml`, `expiry.yml`; pending: first runs |
| 20 | A known-vulnerable dependency ships                              | Grype before signing (fails on fixable critical), weekly Grype on `:stable`, OSV-Scanner on pip and Gradle, Dependabot | `device-images.yml`, `security.yml`; OSV-Scanner run locally 2026-11-07 |
| 21 | Production data lost                                             | nightly dump, encrypted with age, kept 90 days              | `backup.yml`; pending: recipients file and a restore test |
| 22 | The decoy file share becomes a way in: a sign-in or guest access that works, a writable share, real data | no account exists, failed sign-ins never become guest sessions, only Public opens without a password and it is read-only, its files are dull text written for the decoy and baked into the image | tested: `device/e2e/run.py` (sign-in refused, bait file read); verified 2026-11-07 on the built image under Podman 5.4.2 with the units' flags: every password share refuses anonymous access, sign-in and writes refused |
| 23 | Hostile user, share or file names from the file share            | only the user name, share and file are kept, bounded and stripped; Samba logs no password or hash; fixed detail keys `share` and `file` | tested: agent `test_sources` (real Samba records), `18_file_access` |
| 24 | Discovery traffic floods the owner with alerts                   | NetBIOS, mDNS and WS-Discovery are answered but never parsed; listing the shares (`IPC$`) and clients probing without a password are not events | tested: agent `test_sources`; `device/e2e/run.py` (anonymous listing yields no sign-in event) |
| 25 | Exhausting the device through the file share                     | at most 20 smbd processes, state in a small tmpfs, logs rotated at 1 MB | config review: `canary/persona/smb.conf` |

Further: typed passwords never leave the device (tested: agent `test_sources`,
end-to-end assertion that the attacker's password, typed into the web page
and the file share, is absent from the backend).

Hardware (verified 2026-11-07, `docs/STATUS.md` Phase 7): from the LAN only
the eight decoy ports answer and port 22 is the decoy, not the host's SSH;
the login alert arrived about 1 s after the attempt with the fixed template.
The file share and its announcements (ADR 0018) are pending on hardware:
the new ports from the LAN, the decoy in Windows Explorer and Finder, and
port 5000.

## Outbound traffic from the device

Everything the agent sends besides the decoys' answers. Nothing listens for
it: every answer belongs to a connection or query the agent opened, so the
firewall needs no new rule.

| To                                   | What                                               | When                         | Protection |
| ------------------------------------ | -------------------------------------------------- | ---------------------------- | ---------- |
| The backend (Supabase), port 443     | device RPCs; a TCP connection with no data as the internet probe | events as they occur, heartbeat and probe every minute, inventory every 15 min, Wi-Fi watch every 5 min, health report hourly | TLS, system CAs |
| The default gateway                  | ICMP echo                                          | every minute                 | none needed: carries nothing |
| The default gateway, its UPnP port   | HTTP: the IGD description and `GetGenericPortMappingEntry` | hourly, if it announced an IGD | read-only; cleartext because UPnP has no TLS, carries no secret, answers treated as hostile |
| The LAN name server, UDP 53          | one A lookup of the backend's name; 6 lookups of fixed public names | every minute; hourly | cleartext as DNS on a LAN is; answers matched by random ID on a connected socket |
| 1.1.1.1, port 443 (Cloudflare)       | DNS over HTTPS: 6 lookups of fixed public names    | hourly                       | TLS, certificate checked against the address |
| speed.cloudflare.com, port 443       | one download of at most 25 MB and 30 s             | at night, only while the owner has the speed test on | TLS, system CAs |
| LAN multicast and broadcast          | discovery searches and name poisoning and DHCP probes (ADR 0003, 0010) | every 15 min | send only |
| Wi-Fi probe requests (the host's scan) | an active scan, no association (ADR 0019)    | every 5 min, only with a Wi-Fi name and a known country | joins no network; see "Radio use" |

No request carries owner data. The DNS over HTTPS and speed test endpoints
are fixed in `device/agent/dekoi_agent/`; the backend stores the device's
public address and country (from request headers) for its owner only.

## Radio use (Wi-Fi watch, ADR 0019)

- The Pi's Wi-Fi radio stays rfkill-blocked, as Raspberry Pi OS ships it,
  until the owner has given a Wi-Fi name and the backend knows the device's
  country. Then `dekoi-wifi-scan` sets the country (`raspi-config`) and runs
  `iw dev wlan0 scan` every five minutes. Active scanning sends probe
  requests; once the radio is on, NetworkManager may scan too. Nothing ever
  joins a network: a wired DeKoi has no Wi-Fi profile, and the scan never
  runs while wlan0 is connected or carries the default route.
- The scan unit (`dekoi-wifi-scan.service`) runs as root with only
  CAP_NET_ADMIN, a read-only file system except the scan directory, the
  kernel command line and the saved rfkill state, a private /tmp and no raw
  sockets. It reads nothing from the agent but the country.
- The country comes from the `cf-ipcountry` header of the device's own
  request. A wrong country only affects that device's radio; unknown means
  off. Whether the header reaches PostgREST on the hosted project is
  pending verification.
- Residual risk: iw prints WPS strings as raw bytes, so a crafted beacon can
  add lines to the scan text. It cannot make an open access point look
  protected (the privacy bit is printed from the kernel before any
  element), but a beacon that puts its WPS element before its name could
  start a forged block and hide a copy from the parser. Reading scan
  results from nl80211 directly in the agent would close this; not done.
- Scan output and the country live in `/run` (memory), so the card sees no
  writes; what the watch learned is in `wifi.json` next to the agent state.

## OWASP Top 10 (2025)

| Risk                                   | Control in DeKoi                                                                 |
| -------------------------------------- | -------------------------------------------------------------------------------- |
| A01 Broken access control              | RLS on all tables, exact grants asserted by test, no admin path in any client    |
| A02 Security misconfiguration          | Unused Supabase features off, config in git and applied by CI (ADR 0002), advisors fail CI at warn |
| A03 Software supply chain failures     | Pinned dependencies with hashes, image digests, Actions by SHA (enforced by the repository setting), every downloaded tool checked against a pinned SHA-256, signed images, Grype and OSV-Scanner, Dependabot with a week's cooldown, Gradle wrapper verified |
| A04 Cryptographic failures             | TLS only (app network config, agent refuses http), hashed device secrets and codes, no custom crypto |
| A05 Injection                          | Static SQL only, typed RPC arguments, attacker text stored as data and rendered as plain text |
| A06 Insecure design                    | Threat model above; decoys isolated from secrets; device API versioned          |
| A07 Authentication failures            | Supabase Auth with Google ID tokens and nonce; device secrets 256-bit           |
| A08 Software or data integrity failures| Signing key in an HSM, used only after the owner approves a release; signature verified before tag move (CI) and before run (device); rollback on failed update |
| A09 Logging and alerting failures      | Offline detection, push alerts, CI on every change; devices keep journals in memory only |
| A10 Mishandling of exceptional conditions | Fail closed: refused credentials back off, malformed batches dropped, no catch-all handlers |

## OWASP API Security Top 10 (2023)

Object-level and function-level authorization (API1, API5): RLS and exact
function grants, tested. Authentication (API2): Supabase Auth, device secrets.
Property-level authorization (API3): column grants (only `name`,
`speed_test`, `wifi_name`, `trusted`, `label`, `room` and `weekly_summary`
are writable), tested. Resource consumption (API4): batch limits, pending-claim
limit, bounded fields, write limits per address and per user, storage
limits per account and device, a cap on notify calls (ADR 0016), tested;
reads are bounded by statement timeouts and `max_rows`, with aggregates and
query plans off; no edge firewall without a custom domain.
Business flows (API6): single-use pairing. SSRF (API7): the only outbound call
from the backend goes to a fixed FCM URL. Misconfiguration (API8): see A02.
Inventory (API9): one documented API (`docs/architecture.md`). Unsafe
consumption (API10): device input validated server-side even though our agent
validates first.

## OWASP MASVS (L1) for the app

Storage: no backups or device transfers of app data; session in app-private
storage. Crypto: platform TLS, system CAs only. Auth: Credential Manager with
nonce. Network: cleartext forbidden in release. Platform: no exported components
except the launcher; no WebView; confirmations that delete data hide other
apps' overlays (Android 12+) and ignore touches through them (**pending**).
Code: R8 in release, lint clean, dependencies pinned. Resilience: out of MVP
scope.

## Platform settings

Auth, from `backend/supabase/config.toml` and applied by CI: sign-up open,
through Google only, with the nonce checked; email, phone, anonymous, Web3
and passkey sign-in, manual linking and MFA enrolment off; no redirect URLs
besides the site URL; access tokens valid 15 minutes; refresh tokens single
use, with reuse after 10 seconds ending the session; Auth's own rate limits
per address (Google sign-in and refresh share `token_refresh`, verified in
the Auth source). PostgREST, from a migration: the pre-request check of
ADR 0016, no OpenAPI description, no aggregates, no query plans. Only the
`public` schema is exposed; Realtime has no published tables and Storage
no buckets (`00_hardening`).

Settings that the current CI token cannot write. Each needs the owner once:
either a one-time bootstrap check, or a replacement token with the named
permission so CI owns the setting.

| Setting | Required | How |
| ------- | -------- | --- |
| Realtime service | off: nothing in DeKoi uses Realtime, and while it runs anyone with the publishable key can broadcast on public channels and use up the plan's Realtime quota | Management API `PATCH /v1/projects/{ref}/config/realtime` with `{"suspend": true, "private_only": true}` from CI (token permission "Realtime Config" read-write), or Realtime, Settings, "Enable Realtime service" |
| SSL enforcement for direct database connections | on | `[db.ssl_enforcement] enabled = true` in `config.toml` once the token has "SSL Enforcement" read-write; `config push` fails on it before |
| Auth audit logs in the database | off: the rows hold addresses and are not deleted with the account | dashboard only: Authentication, Audit Logs, "Write audit logs to the database" |
| Auth IP address forwarding (`Sb-Forwarded-For`) | off (the default for new projects): otherwise a caller with a secret key chooses the address Auth counts | dashboard only: Authentication, Rate Limits |

## Personal data

What the backend stores about people, for the privacy policy. The jobs
that delete it are in `docs/architecture.md`, "Scheduled jobs".

| Data | About | Where | Kept |
| ---- | ----- | ----- | ---- |
| Google account: email address, name, picture URL, Google account ID, sign-in times | the owner | Supabase Auth (`auth.users`, `auth.identities`) | until the account is deleted |
| Sessions: address and user agent of each sign-in and refresh | the owner | `auth.sessions`, `auth.refresh_tokens` | until sign-out or account deletion; the Free plan has no session expiry |
| Push tokens | the owner's phones | `private.push_tokens` | until sign-out, until FCM reports the app removed, beyond the 10 newest, or account deletion |
| DeKoi names, local and public address, country, versions, decoys, monitors, online state, network check results, speed test setting, home Wi-Fi name and Wi-Fi watch state | the owner | `public.devices` | until the DeKoi or the account is removed; a setup never started is removed after 30 days |
| Settings and when the last weekly summary was sent | the owner | `public.owner_settings`, `private.weekly_summaries` | until the account is deleted |
| Events: source IP and hardware address, user names and paths typed into decoys, user agents, IDS signatures, the hardware address, security, channel and signal of access points that carry the home Wi-Fi name (possibly a neighbour's), when the owner read them | people and devices on or near the owner's network | `public.events` | 90 days |
| Network inventory: hardware and IP addresses, maker, host names (often a person's name), model, kind, system, services, trust, the owner's names and rooms for them | devices on the owner's network | `public.network_hosts`, `public.networks` | 90 days after last seen |
| Network health, speed tests, port forwards (internal address and the description any device on the network chose) | the owner's network and its devices | `public.health_samples`, `public.speed_tests`, `public.port_forwards` | 90 days |
| Source of the last push per DeKoi | devices on the owner's network | `private.notified_sources` | 1 day |
| Client address (IPv6 by its /64) or user ID with a request count | anyone writing to the API | `private.rate_counters` | at most 70 minutes |
| API, Auth and function logs with client addresses | anyone calling the API | Supabase's log store | 1 day on the Free plan |
| Encrypted backups of all of the above | everyone above | GitHub Actions artifacts | 30 days (`docs/runbooks/restore-backup.md`) |

Never stored: passwords typed into decoys (they never leave the device),
traffic contents, precise location (the country comes from the platform's
`CF-IPCountry` header), the names of other Wi-Fi networks. The access
points the Wi-Fi watch learned stay on the DeKoi (ADR 0019).

Deleting the account (`delete_account_v1`) deletes the Auth user, and every
other table refers to `auth.users` or to such a table with
`on delete cascade`. `04_account` checks both: the deletion itself, and that
every table in `public` and `private` is reached that way, so a table added
later cannot be forgotten. What remains afterwards: rate counts for up to
70 minutes, platform logs for a day, backups for 30 days.

Processors: Supabase (database, Auth, functions; EU, Stockholm), Google
(sign-in; FCM receives the push token, a fixed notification text and the
event id), GitHub (encrypted backups, which it cannot read).

## Monitoring

What should run on a schedule, for the CI work to build. Each is a
read-only check against the project with the CI token or database
password; a failing run emails the owner. SQL checks connect as `postgres`.

| Check | Fails when | When |
| ----- | ---------- | ---- |
| Advisors: `supabase db advisors --remote --type all --fail-on warn` | any warning. The project's run adds Auth lints; ignore by name only those that cannot apply to Google-only sign-in on the Free plan (leaked password protection, MFA options), nothing else | daily and after every deploy |
| Configuration drift: `supabase config diff --remote`, and `GET /v1/projects/{ref}/config/realtime` | a difference from `config.toml`, or Realtime not suspended (see "Platform settings") | daily |
| Surface checks in `docs/runbooks/deploy-backend.md` | any unexpected status | daily |
| Backup and restore test (`docs/runbooks/restore-backup.md`) | any step | daily |
| Jobs: `cron.job_run_details` of the last day | a status other than `succeeded`, or a job of `cron.job` without a run | daily |
| Database size: `pg_database_size(current_database())` | over 350 MB, 70% of the Free plan's 500 MB | daily |
| Notify quota: events received this calendar month, an upper bound on `notify` calls | over 400,000 of the Free plan's 500,000 | daily |
| Notify gate: hours of the last day with more than 600 events | any: pushes were held back for everyone (ADR 0016) | daily |
| Flooding: per account, devices, setups waiting, reported events of the last day and push tokens | any account over half its limit (ADR 0016), or setups waiting over 100 in all | daily |
| Client address: `private.rate_counters` holds `ip` buckets while devices are online | none: the platform no longer sends `CF-Connecting-IP`, and the per-address limits are off | daily |
| CI access token expiry (`docs/runbooks/deploy-backend.md`, step 4) | less than 30 days left; a reminder in the owner's calendar, since the token cannot read its own expiry | monthly |

## Known limitations

See `docs/STATUS.md`, "Known limitations".
