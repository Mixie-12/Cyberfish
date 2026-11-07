# DeKoi build brief

You are the lead engineer for DeKoi by CyberFish. Build the MVP from an empty
repository to a running system: a Raspberry Pi honeypot, an SD-card setup tool,
an Android app, and the backend that connects them.

Work through the phases in section 6 autonomously. The job is finished only when
every item in section 8 (Definition of done) has been demonstrated on real
infrastructure and real hardware. Code that compiles is not done. Code that
passes tests but has not run end to end is not done.

Read `CLAUDE.md` before anything else. It defines how you write code, commit,
and verify. This file defines what to build and in which order. If the two ever
conflict, stop and ask the owner.

If your context is reset mid-build, resume from `docs/STATUS.md`.


## 1. Product

DeKoi is a plug-and-play network honeypot (a "canary") for homes and small
offices. It is a Raspberry Pi that pretends to be a valuable machine on the
local network, such as a storage box. Nothing legitimate has a reason to talk to
it, so any interaction is a strong signal that someone or something on the
network is probing: an intruder, a compromised device, or malware spreading.
When that happens, the owner's phone gets an alert that explains in plain
language what happened and what to do.

The target user is not technical. They never see a terminal, an IP
configuration, or a password for the Pi.

The pitch: plug it in, scan a code, done. It updates and repairs itself. The app
makes invisible security visible. It continuously shows that DeKoi is alive and
working (devices seen, decoys running, last check-in), not just silence.

### 1.1 User journey

This is the acceptance script for the user experience. Every step must work
exactly like this on real hardware.

1. Install the DeKoi app on Android and sign in with Google.
2. On a PC, run DeKoi Setup. Insert an SD card. Choose cable (recommended) or
   Wi-Fi (network name, password, country). Click Write.
3. DeKoi Setup writes the card, then shows a QR code and a short name such as
   "DeKoi 7F3K". In the app: Add DeKoi, scan. The app confirms "DeKoi 7F3K is
   linked to your account".
4. Put the card in the Pi, connect the network cable and power. The app shows
   setup progress: linked, first start (can take up to 10 minutes), online,
   protecting. If the device has not come online after 20 minutes, the app shows
   a short troubleshooting checklist.
5. Home screen: "You're protected. Watching 9 decoy services. 18 devices seen on
   your network. Last check-in 1 minute ago."
6. When someone touches a decoy: a push notification within 60 seconds. Tapping
   it opens the event: what happened, why it matters, what to do. Technical
   details are available but collapsed. "I know this device" marks the source as
   trusted; later activity from it is still recorded but does not notify.
7. If the Pi is offline for more than 30 minutes: a push notification.

There are no other steps. No accounts on the Pi, no SSH, no web interface on the
Pi.


## 2. Principles behind every decision

- MVP: the smallest amount of code and the fewest moving parts that deliver the
  journey above. The quickest path to a working, secure system wins.
- Off-the-shelf, well-vetted components over custom code. Our code is glue.
- Security by construction: deny by default, least privilege, small attack
  surface, no secrets in clients or in the repository.
- Long-term maintainability: devices in the field must keep working across
  backend and app releases.
- No over-engineering: no microservices, no message queues, no Kubernetes, no
  custom auth, no custom crypto, no custom update system, no custom disk writer.
- GitOps: every running environment is defined in git and changed only by CI.
  No manual changes in dashboards, no ad hoc commands against production.
  One-time bootstrap steps are written down in a runbook (ADR 0002).


## 3. Architecture (decided)

Deviate only if a decision proves unworkable in practice. Then write an ADR in
`docs/decisions/` explaining why, and tell the owner.

```
Customer LAN                                    Supabase Cloud, EU region
+------------------------------------+         +--------------------------------------+
| Raspberry Pi, Raspberry Pi OS Lite |  HTTPS  | surface in use: /auth/v1 /rest/v1    |
|                                    | ------> |                 /functions/v1        |
|  dekoi-canary  OpenCanary decoys   |  out-   |                                      |
|      | log file, shared, read-only |  bound  |                                      |
|  dekoi-agent   Python stdlib       |  only   | Supabase: Postgres + RLS, Auth,      |
|  nftables + ulogd2  scan detection |         | PostgREST, pg_cron, Edge Function    |
|  podman auto-update, unattended-   |         | "notify"                             |
|  upgrades, hardware watchdog       |         +------------------+-------------------+
+------------------------------------+                            | FCM HTTP v1
                                                                  v
PC: DeKoi Setup --> Raspberry Pi Imager CLI --> SD card     Android app (Kotlin, Compose)
          \--> QR code ----------- scanned by -----------------^
```

### 3.1 Backend: Supabase

Supabase provides Postgres, Row Level Security, Auth, PostgREST, Edge Functions
and pg_cron as one maintained package. Our backend code is SQL migrations,
pgTAP tests, and one Edge Function. There is no custom API server.

Hosting (decided by the owner in Phase 0, see ADR 0001):
- Supabase Cloud, one project in an EU region, API at
  `https://<project-ref>.supabase.co`. No servers of our own.
- The dashboard is protected by the owner's Supabase account with 2FA.
- The Data API exposes only the `public` schema. GraphQL is disabled, there are
  no Storage buckets and no Realtime publications, so the surface in use is
  `/auth/v1`, `/rest/v1` and `/functions/v1`.
- Self-hosting later (for example on the owner's homelab) is the same code;
  switching is a URL and keys.
- Local development and CI use the Supabase CLI (`supabase start`,
  `supabase db reset`, `supabase test db`).

Data model (minimum shape; refine names during implementation):
- `devices`: id, owner_id, name, claim_code_hash (unique, kept forever so codes
  are never reusable), secret_hash (null until the device registers), status,
  agent_version, last_seen_at, created_at.
- `events`: id, device_id, agent_event_id (unique per device, makes retries
  idempotent), occurred_at, received_at, kind, service, src_ip, src_mac,
  dst_port, count, detail (jsonb, size-capped).
- `network_hosts`: device_id, mac, ip, vendor, is_private_mac, trusted,
  first_seen_at, last_seen_at.
- `push_tokens`: user_id, token, updated_at.

Event kinds (part of the device API, see 3.2): `login_attempt`, `connection`,
`port_scan` from the agent; `device_offline`, `device_online` generated by the
backend.

Access rules:
- RLS is enabled on every table, without exception. A test fails the build if
  any table in an exposed schema lacks RLS.
- App users (`authenticated`) can read their own devices, events and
  network_hosts; rename and remove their own devices; mark their own hosts as
  trusted; manage their own push tokens. Nothing else. No direct inserts into
  `devices` or `events`.
- Devices (anon key plus a per-device secret) act only through versioned
  `SECURITY DEFINER` RPCs: `device_register_v1`, `device_heartbeat_v1`,
  `device_events_v1`, `device_hosts_v1`. Each one verifies the secret hash,
  validates input shape, batch size and field lengths, and touches only the
  calling device's rows.
- The `service_role` key exists only in the backend runtime (Edge Function
  environment). Never in the app, DeKoi Setup, the device, or the repository.
- Account deletion (required by Google Play and GDPR) removes the user and
  cascades to all their data.
- RBAC for the MVP: owner (authenticated, row-scoped), device (RPC-scoped),
  backend (service role). No admin backdoor in any client. Shared household
  access is on the roadmap, not in the MVP.
- Retention via pg_cron: events older than 90 days, network_hosts not seen for
  90 days.
- Offline detection via pg_cron every 5 minutes: a device not seen for 30
  minutes gets one `device_offline` event; the next heartbeat produces
  `device_online`.

Pairing protocol:
- DeKoi Setup generates a claim code: 128 bits from a CSPRNG, Crockford base32.
  The QR payload carries a version prefix, for example `DEKOI1:<code>`. The code
  is written to the SD card and shown as a QR code plus a short name derived from
  the code.
- The app scans the code and calls `claim_device_v1(code)`. This creates the
  device row owned by the user. Same user scanning the same code again is
  idempotent. Any other case returns one generic error (no enumeration). Limit
  pending (claimed but not registered) devices per user, for example 5.
- On first boot the agent generates a 256-bit device secret and calls
  `device_register_v1(code, secret, ...)`. It succeeds only for a claimed code
  whose device has no secret yet; it stores SHA-256 of the secret and returns
  the device id. Until the code is claimed, the agent retries with backoff.
- Result: each code can be claimed once and registered once. A claimed device
  can never move to another account. The owner can remove it; to pair again the
  card must be rewritten with a new code.
- Claims are rate-limited per user in SQL. Registration and device calls are
  protected by the 128-bit code and the 256-bit secret.

Notifications:
- Database webhook on `events` insert, Edge Function `notify`, FCM HTTP v1.
  Only Firebase Cloud Messaging is used. No Firebase Auth, database or
  analytics.
- Coalesce: at most one push per device and source per 10 minutes. Sources the
  owner marked as trusted never push. `device_offline` always pushes. Keep the
  coalescing rule in one place.
- Notification text is chosen from fixed templates by event kind. It never
  contains attacker-controlled strings (usernames, paths, banners). The payload
  carries the event id; the app loads details after authentication.

### 3.2 Device

Hardware: Raspberry Pi 4 (2 GB or more) and Raspberry Pi 5. Ethernet
recommended, Wi-Fi supported.

Operating system: the official Raspberry Pi OS Lite 64-bit, current release.
Image URL and SHA-256 are pinned in `device/image.json`, the single source of
truth for DeKoi Setup and CI. No custom OS build for the MVP.

First-boot provisioning: cloud-init `user-data` and `network-config`, written to
the card by DeKoi Setup through Raspberry Pi Imager. Recent Raspberry Pi OS
releases use cloud-init (`cloudinit-rpi`), and Imager 2.x also knows a newer
`rpi-preseed` format. Confirm which one the pinned image uses before building
the templates.

Host configuration (source files in `device/host/`, embedded into user-data
when DeKoi Setup is built):
- Packages: podman, nftables, ulogd2 with JSON output, unattended-upgrades.
- Two Podman Quadlet units, `dekoi-canary.container` and
  `dekoi-agent.container`, with `AutoUpdate=registry` and sd_notify readiness so
  that `podman auto-update` can roll back a failed update.
  `podman-auto-update.timer` enabled.
- `/etc/containers/policy.json`: images from `ghcr.io/<owner>/dekoi-*` must
  carry a valid cosign signature (`sigstoreSigned`, public key on the device).
  All other images are rejected.
- unattended-upgrades for Debian security and the Raspberry Pi archive, with
  automatic reboot in a nightly window.
- nftables: inbound allowed only to decoy ports; new inbound connections to any
  other port are logged to ulogd2 for scan detection; default drop. Outbound is
  open for the MVP (DNS, NTP, HTTPS); document this.
- sshd disabled and masked. No user with a password or SSH key. No console
  autologin. Confirm headless first boot is not blocked by the Raspberry Pi OS
  first-user setup.
- Hardware watchdog through systemd. journald volatile or size-capped to protect
  the SD card. Time sync completes before the agent starts (TLS needs a correct
  clock). avahi and other default network services disabled unless needed.

Host configuration is frozen once the card is written; container images update
automatically. Therefore keep host configuration minimal and generic, and put
everything likely to change inside the images. Moving host configuration into a
signed .deb is a roadmap item.

`dekoi-canary` image:
- OpenCanary from PyPI, version pinned with hashes. Multi-stage build: no
  compilers, sudo or editors in the final image.
- Runs as non-root, no capabilities beyond binding its ports, read-only root
  filesystem, no credentials, no access to the agent's state.
- Default persona: a generic network storage box. Services: HTTP with the NAS
  login skin, HTTPS, SSH, FTP, Telnet, MySQL, RDP, VNC, Redis. No Samba in the
  MVP. The decoy configuration lives in the image.

`dekoi-agent` image (Python, standard library only, plus `arp-scan` and
`ieee-data` from Debian):
- First boot: generate the device secret, register with the claim code.
- Follow the OpenCanary log (handling rotation) and the ulogd2 JSON log. Map
  OpenCanary log types to our event kinds. Turn SYN logs into `port_scan`
  events (for example 5 or more ports from one source within 60 seconds).
  The first occurrence is sent immediately; repeats (same kind, service and
  source within 60 seconds) are collapsed into a count, so a brute-force run
  does not become thousands of rows and the 60-second alert target holds.
- Drop captured passwords before anything leaves the device. We never store
  credentials someone typed. Usernames are kept, truncated.
- Send in batches with retry and backoff. A bounded on-disk queue keeps events
  across backend outages and reboots. `agent_event_id` makes resends safe.
- Heartbeat every 60 seconds with agent version and decoy status.
- Network inventory every 15 minutes with arp-scan (only `CAP_NET_RAW`). Vendor
  from ieee-data. Locally administered (randomised) MACs are flagged as private,
  so the app can say "phone or tablet with a private address".
- Backend URL and anon key are built into the agent image, not written to the SD
  card, so they can be rotated for the whole fleet by an image update.
- sd_notify ready and watchdog pings.
- Everything the canary logs is attacker-controlled: bound its length, strip
  control characters, never interpret it.

### 3.3 DeKoi Setup (SD-card tool)

- Compose Multiplatform Desktop (Kotlin, JVM). Shares design tokens and the
  claim-code format with the app through `clients/shared`.
- Writing is delegated to the Raspberry Pi Imager CLI:
  `rpi-imager --cli --sha256 <hash> --cloudinit-userdata <file>
  --cloudinit-networkconfig <file> <image> <device>`. We do not write a disk
  writer.
- Phase 6 starts with a time-boxed spike proving this end to end on Windows (the
  owner's OS), including: how Imager elevates (UAC) and how progress and errors
  reach DeKoi Setup (for example `--log-file`); drive enumeration that never
  lists system or fixed disks; flags of the current Imager release. macOS and
  Linux use the same code path, best effort for the MVP. If Imager is missing,
  offer to install it (winget on Windows, official download link elsewhere).
- Flow: detect cards, choose network (cable, or Wi-Fi with name, password,
  country), confirm erase showing the card's size and label, write and verify,
  then show the QR code and short name with "Insert the card and plug in your
  DeKoi".
- If the CLI path is not viable on Windows, stop and present options to the
  owner (for example Imager GUI with a DeKoi custom repository, after which
  DeKoi Setup writes the cloud-init files to the mounted boot partition). Do not
  write a raw disk writer.

### 3.4 Android app

- Kotlin, Jetpack Compose, Material 3 with a custom restrained theme. Single
  activity. ViewModel and StateFlow. Manual dependency wiring, no DI framework.
  No local database for the MVP.
- Libraries: supabase-kt (auth, postgrest) on Ktor with OkHttp; Credential
  Manager with Sign in with Google (ID token to Supabase `signInWithIdToken`,
  with nonce); Google code scanner from Play services (no camera permission);
  Firebase Cloud Messaging. Anything else needs a reason in the commit message.
- Screens: Sign in; Add DeKoi (scan, manual code entry as fallback); Setup
  progress with troubleshooting; Home (status, key numbers, latest activity);
  Activity (timeline); Event detail (what happened, why it matters, what to do,
  technical details collapsed, "I know this device"); Network (devices seen, new
  ones marked); Device (name, status, version, remove); Settings
  (notifications, sign out, delete account, privacy).
- Plain language is the feature. One mapping from event kind and service to
  title, explanation and recommended actions, in string resources. Written for a
  non-technical reader. English first; every string in resources so
  translations can follow.
- Design: calm and restrained, in the spirit of Apple's apps. One accent colour,
  neutral surfaces, large clear typography, generous spacing, few elements per
  screen, subtle motion, full dark mode. Accessible: TalkBack labels, WCAG AA
  contrast, font scaling. Home always shows one of four states: Protected,
  Attention needed, Offline, Setting up.
- Security: only the public URL and anon key in the app; HTTPS only (network
  security config forbids cleartext); `allowBackup=false`; R8 in release; no
  logging of tokens or event data in release builds; attacker-controlled fields
  rendered as plain text only.
- Data refreshes on open, on pull-to-refresh, and on push. No realtime
  subscription in the MVP.
- Distribution for the MVP: signed release APK on GitHub Releases. Google Play
  comes later.

### 3.5 CI/CD (GitHub Actions)

- One workflow per component, triggered by path filters:
  - backend: Supabase CLI start, `db reset`, pgTAP, RLS coverage check;
  - device: ruff, pytest, shellcheck, hadolint, image build for arm64 and amd64,
    end-to-end harness;
  - clients: Gradle build, lint, unit tests;
  - release (on `v*` tags): build, sign images with cosign, push to GHCR, move
    `:stable`; signed release APK; DeKoi Setup installers; GitHub Release with
    SHA-256 checksums.
- Pushes to `main` produce `:edge` images. Only release tags move `:stable`,
  which is what devices follow.
- Weekly scheduled rebuild of device images to pick up Debian security fixes.
  Dependabot for Gradle, pip, Docker and GitHub Actions.
- Actions pinned by commit SHA. Minimal `permissions:` per job. Secrets only in
  release jobs. gitleaks on every push. No CodeQL: the repository is private
  and CodeQL needs paid GitHub Advanced Security there.
- arm64 images are built under QEMU, only on `main` and tags, to stay within
  the free Actions minutes of a private repository. GHCR packages are set to
  public so devices pull without credentials.
- Deployment is GitOps (ADR 0002). A push to `main` that passes the backend
  tests deploys migrations, project configuration and Edge Functions with
  `supabase db push`, `supabase config push` and `supabase functions deploy`
  against `--remote=<project ref>`. A `v*` tag releases device images, the
  APK and installers. CI holds the Supabase access token and database
  password as repository secrets, used only by the deploy job on `main`.


## 4. Security requirements

Write `docs/security.md` containing:
- a threat model: assets, actors, trust boundaries, and the abuse cases below;
- a mapping of the current OWASP Top 10 (2025), the OWASP API Security Top 10
  (2023) and OWASP MASVS (L1, for the app) to concrete controls in this system,
  each with the test or evidence that proves it.

Abuse cases that must be covered by automated tests or recorded evidence:

1. User A reads, changes or deletes anything belonging to user B, through any
   table or RPC. (pgTAP)
2. An anonymous caller reads any table. (pgTAP)
3. Claim code guessing, reuse, claiming an already claimed code, exceeding the
   pending limit. (pgTAP)
4. A device with a wrong secret writes data; a device writes into another
   device's rows. (pgTAP)
5. Oversized or malformed batches; strings with control characters, ANSI
   escapes or HTML; oversized detail JSON. (pgTAP and agent tests)
6. An attacker gains code execution inside the canary: cannot read the device
   secret, cannot reach backend credentials, cannot write to the host.
   (container configuration review and a test)
7. An unsigned or wrongly signed image is pushed to GHCR: the device refuses it.
   (recorded test)
8. Attacker-controlled strings reach notification text. (impossible by design;
   unit test on `notify`)
9. Anything beyond the intended surface is reachable on the project URL:
   tables or functions without grants, GraphQL, Storage buckets, Realtime
   publications, schemas other than `public`. (checks in the deploy runbook,
   run from outside)
10. Secrets anywhere in git history. (gitleaks)

At the end of every phase that touches a trust boundary, run a focused security
review of the changes (the `security-review` skill or equivalent) and fix the
findings before moving on.


## 5. Repository layout

```
CLAUDE.md                 engineering rules, loaded every session
README.md                 what DeKoi is, repository map, quick start
.gitattributes            LF line endings everywhere
.github/workflows/        CI/CD
docs/
  PROMPT.md               this brief
  STATUS.md               progress, next steps, blockers, owner actions
  architecture.md         components, data flow, device API contract
  security.md             threat model, OWASP mapping, evidence
  decisions/              ADRs, one page each at most
  runbooks/               setup-and-pair, deploy-backend, release,
                          rotate-keys, restore-backup
backend/
  supabase/               Supabase CLI project: config, migrations, functions,
                          pgTAP tests
device/
  image.json              pinned Raspberry Pi OS image URL and SHA-256
  cloud-init/             user-data and network-config templates
  host/                   files cloud-init installs on the Pi
  canary/                 dekoi-canary image
  agent/                  dekoi-agent image and tests
  e2e/                    local end-to-end harness
clients/                  Gradle root
  shared/                 claim-code format, design tokens
  app/                    Android app
  burner/                 DeKoi Setup (desktop)
```

Each component has a README with build, test and run commands, and nothing
else.


## 6. Execution plan

Every phase follows the same loop: plan, implement in small commits, tests green
locally and in CI, push, update `docs/STATUS.md` (done, next, blockers, owner
actions), short report to the owner. Do not start a phase while the previous
phase's checks are red. Work that does not depend on owner input never waits for
it.

### Phase 0: Bootstrap
- Check the toolchain on this Windows machine: git, gh, Docker Desktop with
  WSL2, Supabase CLI, JDK (current LTS supported by the Android Gradle plugin),
  Android SDK, Python, Deno. List what is missing and ask before installing.
- Ask the owner for all inputs in section 7 in one message.
- Repository skeleton: README, `.gitignore`, STATUS.md, ADR 0001 (the
  architecture in this brief), CI skeleton with gitleaks.

### Phase 1: Backend schema and security core
- Migrations, RPCs, RLS policies, pg_cron jobs, development-only seed data,
  pgTAP tests for abuse cases 1 to 5, RLS coverage check, CI workflow.

### Phase 2: Device stack
- Canary and agent images, host files, cloud-init templates.
- End-to-end harness in `device/e2e`: local Supabase, both containers, and an
  attacker container that performs an SSH login attempt, an HTTP login attempt
  and a port scan; the harness asserts the resulting rows in the database. CI
  runs it on every device or backend change.
- Image signing in CI, and a test that the Podman policy rejects an unsigned
  image.

### Phase 3: Backend deployment (needs owner input)
- Supabase Cloud project created by the owner (EU region, 2FA on the account,
  an account used for nothing else). The owner stores the project ref as a
  repository variable and the access token and database password as
  repository secrets; from then on the deploy job in CI applies migrations,
  configuration (Auth set to Google only, unused features off) and functions.
- Backups: the Free plan has no downloadable backups. Document a
  `supabase db dump` procedure and test one restore into a local stack. Before
  real customers, the owner decides on a paid plan with daily backups.
- Verify the exposed surface from outside (section 4, case 9). Write the
  runbook.

### Phase 4: Android app
- Sign in, claim, setup progress, home, activity, event detail, network, device,
  settings, account deletion.
- Unit tests for the plain-language mapping and claim-code parsing; one UI test
  for the main flow. Signed release APK from CI.

### Phase 5: Notifications
- `notify` Edge Function, database webhook, coalescing, offline detection.
  FCM in the app, including the Android 13+ notification permission flow.
- Tests: unit tests for the function. The end-to-end harness confirms a push
  request is produced; the FCM endpoint is configurable for tests only.

### Phase 6: DeKoi Setup
- Time-boxed spike (section 3.3), then the implementation, then installers built
  in CI.

### Phase 7: Real hardware, hardening, release
- With the owner, run the full user journey (section 1.1) on real hardware
  against the deployed backend. Run every check in section 8. Fix what is found.
  Finish the docs and runbooks. Tag `v0.1.0`.


## 7. Inputs needed from the owner

Ask for all of these once, in Phase 0. Offer the default where one exists.
Record the answers in `docs/STATUS.md`.

1. Backend hosting: self-hosted Supabase on the homelab (default) or Supabase
   Cloud.
2. The l0ki domain, whether its DNS is on Cloudflare, and the API hostname to
   use.
3. Homelab host: OS, Docker available, free RAM and disk, how commands are run
   there (the owner runs them, or SSH access for you).
4. GitHub repository visibility. Public enables free arm64 runners and CodeQL.
   GHCR images must be public so devices can pull them without credentials.
5. A Firebase project (for FCM only) and a Google OAuth client for sign-in. You
   provide the exact steps; the owner creates them.
6. Android application ID. It is permanent once published. Suggest one, for
   example `no.cyberfish.dekoi`.
7. Hardware on hand: Pi model(s), SD cards, card reader, an Android phone for
   testing.
8. License for the repository. Default: proprietary, no license file.
9. Secrets the owner creates and stores, with commands you provide: Android
   release keystore, cosign key pair, Supabase JWT secret and keys, Cloudflare
   tunnel token. They go into GitHub Actions secrets or the homelab `.env`.
   Never into the repository, and never pasted into chat.

Physical steps belong to the owner. For each one, give a short numbered
checklist, then verify the result yourself through logs, the database, or the
app.


## 8. Definition of done

Everything below verified, with the evidence recorded in `docs/STATUS.md`.

1. CI green on `main` for all workflows, including the backend deploy job.
   Release `v0.1.0` published with signed artifacts and checksums. Production
   holds nothing that was not applied by CI from git.
2. Backend live on Supabase Cloud. The surface checks of section 4, case 9
   pass from outside. The Supabase account uses 2FA. One dump-and-restore
   tested.
3. The pgTAP suite, including every abuse case, passes in CI and against a fresh
   copy of the deployed schema.
4. The device end-to-end harness passes in CI.
5. Real hardware: on the owner's Windows PC, DeKoi Setup writes a card; the Pi
   boots; the app shows it online within 15 minutes with no other steps.
6. From another machine on the LAN, an SSH login attempt, an HTTP login attempt
   and an nmap scan each produce the right plain-language event and a push
   within 60 seconds. Passwords typed by the "attacker" appear nowhere in the
   backend.
7. nmap against the Pi shows only decoy ports. No real login is possible.
8. Network cable out for 35 minutes: offline push. Cable back: online.
   Power-cycled: recovers unattended.
9. Updates: a new signed `:stable` agent image is picked up (timer triggered
   manually for the test) and the new version shows in the app; a deliberately
   broken image is rolled back automatically; an unsigned image is refused.
   `unattended-upgrade --dry-run` shows the intended origins; the reboot window
   is set.
10. App: release build on a physical phone, full journey works, account deletion
    removes all of the user's data (verified in the database).
11. `docs/security.md` complete. gitleaks clean on full history. No open high or
    critical CodeQL or Dependabot alerts.
12. Runbooks written and followed at least once: setup-and-pair (for end users),
    deploy-backend, release, rotate-keys, restore-backup.


## 9. Out of scope for the MVP

Do not build these. Record them as the roadmap in `docs/STATUS.md`.

iOS app; web dashboard; decoy personas selectable in the app; shared household
access and roles beyond owner; email sign-in; custom OS image build (pi-gen or
rpi-image-gen); host configuration as a signed .deb; mDNS advertising of the
decoy; realtime updates in the app; Google Play listing; code-signing
certificates for DeKoi Setup installers; billing; staged fleet rollouts;
multi-region hosting.


## 10. Working rules for this build

- Verify versions, flags, config formats and APIs against current official
  documentation before relying on them. Upstream changes; this brief can be out
  of date on details, never on goals.
- When something is ambiguous and security-relevant, costs money, creates an
  external account, or is irreversible: ask the owner. Otherwise take the
  simplest option, record it, and continue.
- Never weaken a security control to make something work. If blocked, stop and
  explain.
- Report honestly: what was verified and how, and what was not.
