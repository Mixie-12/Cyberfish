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
| Anyone on the customer LAN    | decoys, the firewall, broadcast traffic    | the agent, its secret, the host          |
| A compromised router          | answers to the agent's own requests        | the agent's secret, any change on the router made by DeKoi |
| A compromised decoy process   | its own container, write-only log volume   | secrets, the backend, the host           |
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
| 6  | Code execution inside the canary                                 | own user namespace, no capabilities, read-only root, no secrets, write-only log volume | tested (lockdown starts under real Podman): `device/e2e/run.py`; config review: `dekoi-canary.container` |
| 7  | Unsigned, foreign or edge image pushed to the registry           | Podman `policy.json`: only the three release repositories, signed with a key on the card; edge images unsigned in separate repositories | tested: `device/tests/signatures.sh` with the image library of Debian trixie, the card's release (`signatures` job), `test_host_config` |
| 8  | Attacker-controlled strings in notifications                     | fixed templates by event kind; payload carries the event id only; the weekly summary is a fixed template with three whole numbers and no payload | tested with Phase 5 (`notify` function tests) |
| 9  | Anything beyond the intended surface on the project URL          | public schema only, GraphQL dropped, no buckets, no publications | verified 2026-11-07 against production: anonymous reads 401, private schema and GraphQL not exposed, no buckets, notify refuses requests without the secret |
| 10 | Secrets in git history                                           | secrets only in GitHub environments, Cloud KMS and 1Password; push protection | tested: gitleaks over the whole history on every push (`repo.yml`) |
| 11 | A hostile router or resolver feeds the agent crafted UPnP or DNS answers | requests to the gateway's own address only, read-only UPnP action, bodies bounded in size and time, XML parsed as UTF-8 and refused with a document type (no entity expansion), every value validated; DNS replies matched to the query and parsed by the bounded discovery parser | tested: agent `test_upnp`, `test_dns_check`, `test_health` |
| 12 | A device reports forged network health                           | secret-checked RPC, bounded arrays, repeated keys refused, table constraints; the public address is taken from the request, never from the device | tested: `10_network_health` |
| 13 | Signing or deploying code nobody approved                        | `permissions: {}` in every workflow; signing only in the `release` environment (v* tags, owner approval), bound by workload identity federation to that workflow, ref and environment; production secrets only on `main` | config: `infra/github/apply.sh`, `infra/google/github.tf`; pending: repository public and `apply.sh` run |
| 14 | A leaked CI credential stays useful                              | KMS signing key cannot be exported; webhook secret and FCM key replaced monthly and never in GitHub; no database password in CI; an issue 30 days before the other credentials expire | `rotate-keys.yml`, `expiry.yml`; pending: first runs |
| 15 | A known-vulnerable dependency ships                              | Grype before signing (fails on fixable critical), weekly Grype on `:stable`, OSV-Scanner on pip and Gradle, Dependabot | `device-images.yml`, `security.yml`; OSV-Scanner run locally 2026-11-07 |
| 16 | Production data lost                                             | nightly dump, encrypted with age, kept 90 days              | `backup.yml`; pending: recipients file and a restore test |

Further: typed passwords never leave the device (tested: agent `test_sources`,
end-to-end assertion that the attacker's password is absent from the backend).

Hardware (verified 2026-11-07, `docs/STATUS.md` Phase 7): from the LAN only
the eight decoy ports answer and port 22 is the decoy, not the host's SSH;
the login alert arrived about 1 s after the attempt with the fixed template.

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
