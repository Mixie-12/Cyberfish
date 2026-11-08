# Status

Updated 2026-11-07. Everything below is on `main` and deployed by CI (the
production backend deploy is green at `b69caca`). The last release is
v0.2.0 (Phase 8: signed images, app, DeKoi Setup); v0.2.1, with Insights,
Norwegian and the work since v0.2.0, comes next
(`docs/runbooks/release.md`).

## Owner answers

| Question        | Answer                                                    |
| --------------- | --------------------------------------------------------- |
| Backend hosting | Supabase Cloud (ADR 0001)                                 |
| Deployment      | GitOps: CI changes every environment (ADR 0002)           |
| Detection scope | Maximum in one box: decoys, IDS, LAN watchers (ADR 0003)  |
| Repository      | Private; no CodeQL                                        |
| Android app ID  | `no.cyberfish.dekoi`                                      |
| License         | Proprietary, no license file (default)                    |
| Commits         | Unsigned commits allowed                                  |

## Secrets

Where each credential belongs and how it is replaced:
`docs/runbooks/rotate-keys.md`. Today every secret is a repository secret,
none is in an environment, and the deploy job still uses
`SUPABASE_DB_PASSWORD`; the owner actions below close that gap. 1Password
vault "Encryption" holds "DeKoi cosign image signing key" (retires
2026-12-31) and "DeKoi Android release signing key" (alias `dekoi`; the
certificate is pinned in `.github/workflows/app-release.yml`).

## Phase log

### Phase 0: Bootstrap (done)
Toolchain with verified downloads; skeleton, ADRs, gitleaks, Dependabot.

### Phase 1: Backend (done)
Six migrations (hardened defaults, schema with RLS, device API v1, app API v1,
jobs, network detection kinds). pgTAP tests cover abuse cases 1 to 5 and the
exact grants of every API role; advisors clean.

### Phase 2: Device (done)
Agent (decoy logs, port scans, ARP sweeps and spoofing, rogue DHCP and IPv6
routers, name poisoning, Suricata alerts), canary, IDS and agent images,
host configuration, first-boot template, image signing. CI end-to-end on a
real runner with the production Quadlet units. Signature enforcement tested.

### Phase 3: Deploy (done)
Production deployed by CI only: Supabase project `rmllpietnopviodbdkky`
(migrations, `notify`, Google sign-in); Google Cloud project
`dekoi-cyberfish` with Firebase and the FCM sender through OpenTofu
(ADR 0009); images signed and public on GHCR. Surface checks from outside
pass (`docs/runbooks/deploy-backend.md`).

### Phase 4: Android app (done)
All screens, plain language for every event kind, Google sign-in with nonce,
QR scanning without camera permission.

### Phase 5: Notifications (done)
Decision function in SQL (coalescing per device and source per 10 minutes),
events trigger through pg_net with the webhook URL and secret in Vault,
`notify` sends FCM HTTP v1 with fixed templates (ADR 0007). FCM tokens are
deprecated in firebase-messaging 26 but still work; app and function move
to installation IDs together later.

### Phase 6: DeKoi Setup (done)
Card files (cloud-init user-data, netplan; hostile Wi-Fi input cannot inject
YAML), Windows card listing (removable, non-system disks only), Raspberry Pi
Imager CLI (ADR 0008); rendered files validated by cloud-init and netplan.

### Phase 7: Hardware test and release (done 2026-11-07)
Released v0.1.0 to v0.1.2 (signed APK, DeKoi Setup MSI, signed images).
Tested with a Raspberry Pi on a home LAN by ethernet, an Honor PTP-N49
phone (Android 16) and the owner's Windows 11 PC on Wi-Fi:

| Step | Result |
| ---- | ------ |
| Release files | APK and MSI SHA-256 match the release; APK signer matches the pinned certificate |
| Sign-in | Failed on v0.1.0 (Google 28444: an Android client ID in `GOOGLE_WEB_CLIENT_ID`); works since v0.1.1; the deploy now checks the ID and secret with Google (ADR 0009) |
| Card | DeKoi Setup with Imager 2.0.8 wrote the card with the cloud-init files |
| Pairing | First QR scan crashed the app (fixed in `86d026d`, released in v0.1.2); second scan paired |
| First start | Online and "You're protected" about 4 minutes after power-on; 8 decoys, 5 hosts |
| Surface | All 65535 TCP ports from the LAN: only the 8 decoy ports open; port 22 is the decoy |
| SSH and HTTP login | Both reported; push about 1 s after the SSH attempt, fixed template |
| Port scan | Reported |
| ARP sweep | Not reported when sent from the Wi-Fi PC; not verified from a wired host |
| Typed passwords | Not shown in the app (stored rows covered by the CI end-to-end test) |

### Phase 8: Identification, redesign, linking, hardening (released in v0.2.0)
Asked for by the owner after Phase 7, beyond `docs/PROMPT.md`; each ADR
records why.

- Device identification and networks (ADR 0010): passive discovery, one
  classification table, `device_hosts_v2`, `public.networks`,
  `events.severity` generated in the database.
- Severity channels (ADR 0011): `notify` picks `alerts_urgent`, `alerts` or
  `updates` and names the source's kind, both from fixed vocabularies.
- App redesign in the CyberFish brand (ADR 0011, 0020): dark only, bundled
  Michroma and JetBrains Mono, Canvas charts, every screen rebuilt
  (`docs/design.md`); DeKoi Setup shares the palette.
- Linking by a card secret (ADR 0017, replaced ADR 0012, which was not
  built): the claim code on the card is the proof of physical access.
- Phone card writer (ADR 0014): a USB card reader on the phone; pinned
  image, partition table written last, whole card read back.
- Alerts as incidents with read state, host names and rooms, and monitoring
  status through `device_heartbeat_v2` (ADR 0013).
- Network health, DNS check, UPnP port forwards, new devices and the weekly
  summary (ADR 0015, 0021).
- Request and storage limits in Postgres, Auth tokens 15 minutes, account
  deletion reaching every table (ADR 0016).
- The visible decoy `family-nas`: file share with bait files, storage web
  page, mDNS, WS-Discovery and NetBIOS announcements (ADR 0018).
- The Wi-Fi watch: `evil_twin` and `new_access_point` alerts (ADR 0019).
- CI (ADR 0022): signing only in the `release` environment, Cloud KMS key
  as code, edge images unsigned, new cards trust only the three release
  repositories; Grype, OSV-Scanner and advisors; monthly key rotation;
  expiry issues; nightly encrypted backups; repository settings as code.
- First-run tour, Learn topics for every event kind, home-screen widget,
  motion and haptics (`docs/design.md`, "Motion"); the alarm sound of a
  very likely attack (ADR 0028).
- Pushes in the phone's language, English or Bokmål, through
  `register_push_token_v2` and `notification_targets_v3` (ADR 0025); the
  severity colour on every notification; "Mark all as read" on Alerts.
- Features screen with live status from the DeKoi's reports and a feature
  tour; the Wi-Fi watch is called Wi-Fi Guard in the app (`docs/design.md`,
  "Features").
- The CyberFish mark drawn from the path in `Images/cyberfish-app-icon.svg`
  with its fill, edge and glow, in the app, widget, status bar icon and
  DeKoi Setup (ADR 0030).

Verified by CI on `main` (`9dc0efd`, `b69caca`): pgTAP 19 files, 417 tests,
advisors clean; `notify` 18 tests; agent 212 tests, ruff clean; app 158,
shared 23 and DeKoi Setup 15 unit tests, ktlint and Android lint clean;
signature policy tests; the end-to-end test with the production units
(every decoy and the announcer, file share sign-in and bait file,
`device_hosts_v2`, `device_wifi_v1`, no typed password in the backend).
Locally: HTTP 429 and the notify gate; backup dump and restore.

v0.2.0 app (2026-11-07): the released APK crashes at every start
(WorkManager 2.7.1 from Glance under R8 full mode; debug builds are not
shrunk, so CI stayed green). Fixed by "app: run WorkManager 2.12 so
release builds start"; the widget showed only its header, fixed by "app:
keep the widget header to its own height". Both verified on the API 37
emulator with a release build signed by the debug key; a fixed release
is not cut yet. CI does not start a release build, so a crash like this
passes it. Google sign-in with debug builds works only on the machine
whose debug key is in `infra/google/main.tf` (`6def...`).

Card writer on the owner's phone (v0.2.2, USB-C reader): choosing the
reader closed the app every time, because libaums 0.10.0 cannot read
sense data; the app now carries the fixed SCSI driver (ADR 0029), proven
against a simulated reader in `ScsiBlockDeviceTest`. Plugging the reader in
could also close it (navigation before the graph existed), fixed in
`ui/App.kt`. With v0.2.3 each write then stopped about a second in, six
times; v0.2.4 sends reads and writes in commands of at most 240 blocks,
as Linux does, and shows the cause under "Technical details". v0.2.6 adds
Reset Recovery after a failed transfer; v0.2.8 does it without libaums'
native code, whose JNI names do not match its classes (ADR 0029).

The owner's reader (MXTronics "MXT USB Device", `aaaa:8816`) still fails:
it dropped off USB once while being plugged in, its first write fails at
random (within half a second, or after up to 34 seconds), and from then
on it answers nothing, not the class reset and not INQUIRY on a fresh
connection, until it is unplugged. Not verified: a full write with any
other reader. DeKoi Setup on a computer writes cards through the
operating system's driver.

Self-installing card (ADR 0031, 0032), Raspberry Pi side: `device/installer`
builds `dekoi-installer.zip` (41 MB; two local builds gave the same
bytes); the `installer` workflow publishes it to
`ghcr.io/mixie-12/dekoi-installer` from `main`. `device/installer.json` is
not pinned yet (`docs/runbooks/release.md`, "Installer bundle"). Verified:
unit tests; under QEMU, the bundle's own arm64 Python downloads and proves
the real image over HTTPS and writes, reads back and finishes it on a loop
device; its busybox runs the DHCP script and reaches NTP. Not verified on
a Pi 4 or 5: drivers, `eth0`, `mmcblk0`, the `ACT` LED, a 1 GB board's
memory.

Needs hardware, not verified: the decoy in Windows Explorer and Finder on
the owner's LAN; the Wi-Fi watch against a real evil twin; the Pi running
the new images after its nightly update (after v0.2.0); the ARP sweep
from a wired PC.
The card writer needs a phone: on macOS the emulator (37.2.13) cannot take
a USB card reader from the system driver. `-qemu -device usb-host,...`
fails with "Service authorization failed", because the emulator lacks the
`com.apple.vm.device-access` entitlement; `-usb-passthrough` is accepted
but does nothing (tested 2026-11-07, Genesys 05e3:0749).

## Owner actions, in order

1. Supabase access token with the permissions in `deploy-backend.md`,
   step 4 (Database read-write and Advisors read are new), stored as
   `SUPABASE_ACCESS_TOKEN` (`rotate-keys.md`); it moves into `production`
   with action 6. Then remove `SUPABASE_DB_PASSWORD` from the deploy job
   (see its comment in `.github/workflows/backend.yml`) and from GitHub.
   Backups, rotation and the advisors need this token.
2. Backup key: create the age key, keep the private key in 1Password and
   commit `backend/backup-recipients.txt` (`restore-backup.md`); then test
   a restore once. Nothing backs up production until then.
3. Suspend Realtime and turn on SSL enforcement: once in the dashboard, or
   let CI own both ("Realtime Config" and "SSL Enforcement" read-write on
   the token of action 1, plus a CI change; `docs/security.md`, "Platform
   settings").
4. Check in the dashboard that Auth audit logs are not written to the
   database and that IP address forwarding is off (same section).
5. Delete the signatures of the `:edge` images that `main` signed with the
   first key before ADR 0022; the Pi's current card accepts them
   (`rotate-keys.md`, "Take back a signed image").
6. Repository visibility: make it public, so the `release` environment can
   require the owner's approval. On a private repository GitHub Pro adds
   environment secrets but not required reviewers (GitHub docs, checked
   2026-11-07). Then run `infra/github/apply.sh` and move the secrets into
   the environments: the Supabase token and Google client secret into
   `production`, the cosign and Android keys into `release` (ADR 0022).
7. Set `EXPIRES_SUPABASE_ACCESS_TOKEN`, `EXPIRES_GOOGLE_WEB_CLIENT_SECRET`
   and `EXPIRES_COSIGN_PRIVATE_KEY` (2026-12-31) as in `rotate-keys.md`.
8. `tofu apply` and `./publish.sh` in `infra/google`
   (`google-firebase.md`), then run `rotate-keys.yml` and check it is green
   (`rotate-keys.md`); then delete `NOTIFY_SECRET` and
   `FCM_SERVICE_ACCOUNT` from GitHub.
9. Decide on Cloud KMS billing (about 1 USD a month) before the first key
   retires on 2026-12-31; then `rotate-keys.md`, "Switch new cards to the
   Cloud KMS key".
10. Decide before real customers: Supabase Pro (daily backups, PITR as an
    add-on, no pausing, session time-outs, higher quotas) and a custom
    domain with a firewall in front of the API (ADR 0016).
11. After v0.2.0 and the KMS switch, rewrite the Pi's card with the phone
    or DeKoi Setup for the new host configuration: the decoy's firewall
    ports, the Wi-Fi scan, trust in the three release repositories only.
    Then retire the first key (`rotate-keys.md`, "Retire the first key").
12. Optional: repeat the ARP sweep from a PC connected by cable.
13. After the first `installer` run on `main`, make the `dekoi-installer`
    package public (`release.md`, "Installer bundle").

## Known limitations

- Host configuration is frozen when a card is written; images update. The
  Pi's current card has the old firewall and no Wi-Fi scan, and trusts
  every repository under `ghcr.io/mixie-12` signed with the first key.
- DeKoi sees traffic to itself and broadcasts, not traffic between other
  devices (ADR 0003); no per-device bandwidth. The HTTPS decoy is off until
  each device has its own certificate.
- IDS rules are as old as the last approved release or weekly rebuild. Not
  verified on hardware: a live IDS alert, DHCP and name-poisoning probes.
  An ARP sweep from a Wi-Fi client was not detected by a wired DeKoi.
- The per-address limits, the public address and country, and the Wi-Fi
  watch's country need `cf-connecting-ip` and `cf-ipcountry` on the hosted
  project; not yet verified. Without them those limits are off and the
  radio stays off.
- No edge firewall without a custom domain (ADR 0016): a flood from many
  accounts can hold back everyone's pushes for an hour or fill the 500 MB
  database within weeks. The checks in `docs/security.md`, "Monitoring",
  run daily (`monitor.yml`, ADR 0027); not yet run against production.
- Free Supabase plan: nightly dumps, no point-in-time recovery; sessions
  never expire; the project may pause after a week of low activity.
- A phone's push language changes when the app next opens signed in;
  Nynorsk phones get Bokmål pushes (ADR 0025).
- libaums builds its native helpers for 4 KB pages, so 16 KB-page phones
  run the app in compatibility mode; without it, the card writer can crash
  mid-write (ADR 0014).
- Incident grouping lives in both `Incidents.kt` and `weekly_summary_v1`.
- Phones with private MAC addresses can reappear as new devices.

## Roadmap (out of MVP scope)

iOS app; web dashboard; decoy personas selectable in the app; correcting a
device's kind (ADR 0010); shared household access and roles beyond owner;
email sign-in; custom OS image build; host configuration as a signed .deb;
mirror-port mode for full network inspection; realtime updates in the app;
Google Play listing; code-signing certificates for DeKoi Setup; billing;
staged fleet rollouts; multi-region hosting; Cloudflare in front of the API.
