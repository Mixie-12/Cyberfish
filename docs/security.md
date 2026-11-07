# Security

How DeKoi is protected, and the test or evidence behind each claim. Status
words: **tested** (automated, runs in CI), **verified** (checked by hand,
date recorded in `docs/STATUS.md`), **pending** (not yet shown).

## Assets

- Owners' data: events, the inventory of their home network, their account.
- Control of the fleet: whoever can make devices run an image controls a
  device inside every customer's network.
- Device credentials (256-bit secrets) and pairing codes.
- Secrets in CI: the image signing key, the Supabase access token and
  database password, the Firebase service account.

## Actors and trust boundaries

| Actor                         | Can reach                                  | Must never reach                         |
| ----------------------------- | ------------------------------------------ | ---------------------------------------- |
| Anyone on the customer LAN    | decoys and their announcements, the firewall, broadcast traffic | the agent, its secret, the host, any real file |
| A compromised router          | answers to the agent's own requests        | the agent's secret, any change on the router made by DeKoi |
| A compromised decoy process   | its own container, write-only log volume   | secrets, the backend, the host           |
| A compromised announcer       | its own container, nothing else            | any volume, events, secrets, the host    |
| Any internet client           | the Data API with the publishable key      | any row, any function except device RPCs |
| A signed-in owner             | their own rows through RLS                 | other owners' rows, device credentials   |
| A stolen or cloned device     | its own device's rows through device RPCs  | other devices' rows, owners' data        |
| Someone with GHCR push access | the registry                               | devices (signature required)             |

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
| 7  | Unsigned or foreign image pushed to the registry                 | Podman `policy.json`: only cosign-signed images from `ghcr.io/mixie-12` | tested: `signatures` job in `.github/workflows/device.yml` |
| 8  | Attacker-controlled strings in notifications                     | fixed templates by event kind; payload carries the event id only; the weekly summary is a fixed template with three whole numbers and no payload | tested with Phase 5 (`notify` function tests) |
| 9  | Anything beyond the intended surface on the project URL          | public schema only, GraphQL dropped, no buckets, no publications | verified 2026-11-07 against production: anonymous reads 401, private schema and GraphQL not exposed, no buckets, notify refuses requests without the secret |
| 10 | Secrets in git history                                           | secrets only in GitHub secrets and 1Password                | tested: `gitleaks` on every push |
| 11 | A hostile router or resolver feeds the agent crafted UPnP or DNS answers | requests to the gateway's own address only, read-only UPnP action, bodies bounded in size and time, XML parsed as UTF-8 and refused with a document type (no entity expansion), every value validated; DNS replies matched to the query and parsed by the bounded discovery parser | tested: agent `test_upnp`, `test_dns_check`, `test_health` |
| 12 | A device reports forged network health                           | secret-checked RPC, bounded arrays, repeated keys refused, table constraints; the public address is taken from the request, never from the device | tested: `10_network_health` |
| 13 | The decoy file share becomes a way in: a sign-in or guest access that works, a writable share, real data | no account exists, failed sign-ins never become guest sessions, only Public opens without a password and it is read-only, its files are dull text written for the decoy and baked into the image | tested: `device/e2e/run.py` (sign-in refused, bait file read); verified 2026-11-07 with Docker on the built image: sign-in and writes refused |
| 14 | Hostile user, share or file names from the file share            | only the user name, share and file are kept, bounded and stripped; Samba logs no password or hash; fixed detail keys `share` and `file` | tested: agent `test_sources` (real Samba records), `18_file_access` |
| 15 | Discovery traffic floods the owner with alerts                   | NetBIOS, mDNS and WS-Discovery are answered but never parsed; listing the shares (`IPC$`) and clients probing without a password are not events | tested: agent `test_sources`; `device/e2e/run.py` (anonymous listing yields no sign-in event) |
| 16 | Exhausting the device through the file share                     | at most 20 smbd processes, state in a small tmpfs, logs rotated at 1 MB | config review: `canary/persona/smb.conf` |

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
| The backend (Supabase), port 443     | device RPCs; a TCP connection with no data as the internet probe | events as they occur, heartbeat and probe every minute, inventory every 15 min, health report hourly | TLS, system CAs |
| The default gateway                  | ICMP echo                                          | every minute                 | none needed: carries nothing |
| The default gateway, its UPnP port   | HTTP: the IGD description and `GetGenericPortMappingEntry` | hourly, if it announced an IGD | read-only; cleartext because UPnP has no TLS, carries no secret, answers treated as hostile |
| The LAN name server, UDP 53          | one A lookup of the backend's name; 6 lookups of fixed public names | every minute; hourly | cleartext as DNS on a LAN is; answers matched by random ID on a connected socket |
| 1.1.1.1, port 443 (Cloudflare)       | DNS over HTTPS: 6 lookups of fixed public names    | hourly                       | TLS, certificate checked against the address |
| speed.cloudflare.com, port 443       | one download of at most 25 MB and 30 s             | at night, only while the owner has the speed test on | TLS, system CAs |
| LAN multicast and broadcast          | discovery searches and name poisoning and DHCP probes (ADR 0003, 0010) | every 15 min | send only |

No request carries owner data. The DNS over HTTPS and speed test endpoints
are fixed in `device/agent/dekoi_agent/`; the backend stores the device's
public address and country (from request headers) for its owner only.

## OWASP Top 10 (2025)

| Risk                                   | Control in DeKoi                                                                 |
| -------------------------------------- | -------------------------------------------------------------------------------- |
| A01 Broken access control              | RLS on all tables, exact grants asserted by test, no admin path in any client    |
| A02 Security misconfiguration          | Unused Supabase features off, config in git and applied by CI (ADR 0002), advisors fail CI at warn |
| A03 Software supply chain failures     | Pinned dependencies with hashes, image digests, Actions by SHA, signed images, Dependabot, ktlint and Gradle wrapper verified |
| A04 Cryptographic failures             | TLS only (app network config, agent refuses http), hashed device secrets and codes, no custom crypto |
| A05 Injection                          | Static SQL only, typed RPC arguments, attacker text stored as data and rendered as plain text |
| A06 Insecure design                    | Threat model above; decoys isolated from secrets; device API versioned          |
| A07 Authentication failures            | Supabase Auth with Google ID tokens and nonce; device secrets 256-bit           |
| A08 Software or data integrity failures| Signature verified before tag move (CI) and before run (device); rollback on failed update |
| A09 Logging and alerting failures      | Offline detection, push alerts, CI on every change; devices keep journals in memory only |
| A10 Mishandling of exceptional conditions | Fail closed: refused credentials back off, malformed batches dropped, no catch-all handlers |

## OWASP API Security Top 10 (2023)

Object-level and function-level authorization (API1, API5): RLS and exact
function grants, tested. Authentication (API2): Supabase Auth, device secrets.
Property-level authorization (API3): column grants (only `name`, `trusted`
and `weekly_summary` are writable), tested. Resource consumption (API4): batch limits, pending-claim
and `speed_test` are writable), tested. Resource consumption (API4): batch limits, pending-claim
limit, bounded fields; no edge rate limit without a custom domain (ADR 0001).
Business flows (API6): single-use pairing. SSRF (API7): the only outbound call
from the backend goes to a fixed FCM URL. Misconfiguration (API8): see A02.
Inventory (API9): one documented API (`docs/architecture.md`). Unsafe
consumption (API10): device input validated server-side even though our agent
validates first.

## OWASP MASVS (L1) for the app

Storage: no backups or device transfers of app data; session in app-private
storage. Crypto: platform TLS, system CAs only. Auth: Credential Manager with
nonce. Network: cleartext forbidden in release. Platform: no exported components
except the launcher; no WebView. Code: R8 in release, lint clean, dependencies
pinned. Resilience: out of MVP scope.

## Known limitations

See `docs/STATUS.md`, "Known limitations".
