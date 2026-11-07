# Status

Updated 2026-11-08. Phases 0 to 6 are on `main`, deployed by CI and released
as v0.1.1. The Phase 7 hardware test passed end to end with the findings
below. Next: device identification and the app redesign the owner asked for
(Phase 8).

## Owner answers (Phase 0 and later)

| Question             | Answer                                             |
| -------------------- | -------------------------------------------------- |
| Backend hosting      | Supabase Cloud (ADR 0001)                          |
| Deployment           | GitOps: CI changes every environment (ADR 0002)    |
| Detection scope      | Maximum in one box: decoys, IDS, LAN watchers (ADR 0003) |
| Repository           | Private; no CodeQL                                 |
| Android app ID       | `no.cyberfish.dekoi`                               |
| License              | Proprietary, no license file (default)             |
| Commits              | Unsigned commits allowed                           |

## Secrets and where they live

| Secret                               | Where                                                     |
| ------------------------------------ | --------------------------------------------------------- |
| cosign image signing key, password   | GitHub secrets `COSIGN_PRIVATE_KEY`, `COSIGN_PASSWORD`; 1Password vault "Encryption", item "DeKoi cosign image signing key" |
| cosign public key                    | `device/host/etc/dekoi/cosign.pub` (on every card)        |
| Android release keystore, password   | GitHub secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`; 1Password item "DeKoi Android release signing key". Alias `dekoi`. Certificate SHA-256 `7F:57:59:C5:01:5B:FC:7A:34:7A:72:35:35:54:07:91:96:E6:21:58:5A:9F:EE:69:44:75:86:A7:E8:43:67:41` |
| Notification webhook secret          | GitHub secret `NOTIFY_SECRET` (generated 2026-11-07; written to Vault and the function by the deploy job) |
| Supabase access token, DB password   | GitHub secrets `SUPABASE_ACCESS_TOKEN`, `SUPABASE_DB_PASSWORD` (docs/runbooks/deploy-backend.md) |
| Google web client secret, Firebase service account | GitHub secrets `GOOGLE_WEB_CLIENT_SECRET`, `FCM_SERVICE_ACCOUNT` (docs/runbooks/google-firebase.md) |

## Phase log

### Phase 0: Bootstrap (done)
Toolchain installed with verified downloads; skeleton, ADRs, gitleaks, Dependabot.

### Phase 1: Backend (done)
Six migrations (hardened defaults, schema with RLS, device API v1, app API v1,
jobs, network detection kinds). 87 pgTAP tests cover abuse cases 1 to 5 and
the exact grants of every API role; advisors clean. CI deploy job ready.

### Phase 2: Device (done, hardware pending)
Agent (decoy logs, port scans, ARP sweeps and spoofing, rogue DHCP and IPv6
routers, name poisoning, Suricata alerts), canary, IDS and agent images,
host configuration, first-boot template, image signing. 46 unit and
consistency tests. CI end-to-end on a real runner with the production Quadlet
units: claim, register, online with all eight decoys, SSH and HTTP login
attempts, port scan, network sweep, network inventory, IDS starting locked
down, no typed password in the backend. Signature enforcement tested.

### Phase 3: Deploy (done)
Production deployed by CI only; see "Done 2026-11-07" below.

### Phase 4: Android app (done, phone test pending)
All screens, plain language for every event kind, Google sign-in with nonce,
QR scanning without camera permission. 12 unit tests, Android lint clean.

### Phase 5: Notifications (done, phone test pending)
Decision function in SQL (coalescing per device and source per 10 minutes,
trusted sources never, device_offline always, device_online never), events
trigger through pg_net with the webhook URL and secret in Vault, `notify`
Edge Function sending FCM HTTP v1 with fixed templates (ADR 0007). 106
pgTAP tests and 8 Deno tests; verified end to end locally against a mock FCM.
The backend deploys without Firebase; pushes start once the service account
secret exists.
App: FCM started from Gradle properties (no google-services.json), token
registered after sign-in and removed before sign-out, alerts channel, tapping
opens the event, one plain-language permission request on Android 13+. FCM
tokens are deprecated in firebase-messaging 26 but still work; app and
function move to installation IDs together later (ADR 0007).

### Phase 6: DeKoi Setup (groundwork done, UI in progress)
Card file rendering (user-data with the host files, netplan network-config;
hostile Wi-Fi input cannot inject YAML; the WPA key is stored, not the
passphrase), Windows card listing (only removable, non-system disks), Imager
CLI arguments; 19 tests; rendered files validated by cloud-init and netplan in
CI. The Imager spike is ADR 0008: Imager downloads and checks the image itself,
needs elevation, and reports only an exit code on Windows.

### Phase 7: Hardware, hardening, release (hardware test done 2026-11-07)
Released v0.1.0 and v0.1.1 (signed APK, DeKoi Setup MSI, signed images).
Tested on a Raspberry Pi on a home LAN by ethernet, an Honor PTP-N49 phone
(Android 16) by USB, and the owner's Windows 11 PC on Wi-Fi:

| Step | Result |
| ---- | ------ |
| Release files | APK and MSI SHA-256 match the release; APK signer matches the pinned certificate |
| Sign-in | Failed on v0.1.0 (Google 28444: an Android client ID in `GOOGLE_WEB_CLIENT_ID`); works on v0.1.1. The deploy now checks the ID and secret with Google (ADR 0009) |
| Card | DeKoi Setup with Imager 2.0.8 wrote the card with the cloud-init files |
| Pairing | First QR scan crashed the app (scanner `MlKitException` not caught, fixed in `86d026d`, not yet released); second scan paired |
| First start | Online and "You're protected" about 4 minutes after power-on; 8 decoys, 5 hosts in the inventory |
| Surface | All 65535 TCP ports from the LAN: only the 8 decoy ports open; port 22 answers with the decoy banner, not the host's SSH |
| SSH and HTTP login | Both reported; push "Someone tried to sign in to your DeKoi" about 1 s after the SSH attempt, fixed template |
| Port scan | Reported |
| ARP sweep | Not reported when sent from the Wi-Fi PC (253 targets); not verified from a wired host, see Known limitations |
| Typed passwords | Not shown in the app; the hardware check of the stored rows was not done (covered by the CI end-to-end test) |

Backend hardening for public sign-up (2026-11-08, not yet deployed): Auth
access tokens 15 minutes; write limits per address and per user in
PostgREST's pre-request function; storage limits per account and device;
a cap on `notify` calls against the Free plan quota; abandoned setups
removed after 30 days; a test that account deletion reaches every table
(ADR 0016, `docs/security.md`). HTTP 429 and the notify gate
verified through the local gateway; backup dump and restore verified
locally.

Integration (2026-11-08, not yet pushed or deployed): the Wi-Fi watch
(ADR 0019) and the request and storage limits (ADR 0016) merged onto
`main` at `0eb5e02`, with three new migrations after the production ones:
`20261107235000_wifi_watch`, `20261108000000_request_and_storage_limits`
and `20261108000100_client_address` (`device_health_v1` takes the public
address from `private.client_ip()`). `12_event_vocabulary` now lists
every event kind with its severity. Locally: pgTAP 18 files, 409 tests,
advisors clean; `notify` 18 tests; agent 196 tests, ruff clean; app 45,
shared 5 and DeKoi Setup 24 unit tests, ktlint and Android lint clean. Not
verified: that `cf-connecting-ip` and `cf-ipcountry` reach
`request.headers` on the hosted project. Without them the per-address
limits are off, no public address is stored and the Wi-Fi watch keeps the
radio off (`docs/security.md`, "Monitoring").

## Owner actions open

1. Optional: repeat the ARP sweep from a PC connected by cable.
2. Realtime: suspend it (nothing uses it; public channels are open to
   anyone with the publishable key). Either give CI a token with "Realtime
   Config" read-write so the deploy sets it, or turn it off once in the
   dashboard (`docs/security.md`, "Platform settings").
3. SSL enforcement for direct database connections: replace the CI token
   with one that adds "SSL Enforcement" read-write; then
   `[db.ssl_enforcement] enabled = true` can go into `config.toml`.
4. Check in the dashboard: Auth audit logs are not written to the
   database, and IP address forwarding is off.
5. Backups: create an age key pair, store the private key in 1Password
   ("DeKoi backup age key") and the public key as the repository variable
   `BACKUP_AGE_RECIPIENT`, so the CI backup job can run
   (`docs/runbooks/restore-backup.md`). Until then nothing backs up
   production.
6. Decide before real customers: Supabase Pro (daily backups, PITR as an
   add-on, no pausing, session time-outs, higher quotas) and a custom
   domain with a firewall in front of the API.

Done 2026-11-07:
- Supabase project `rmllpietnopviodbdkky` deployed entirely by CI (migrations,
  notify function and secrets, configuration with Google sign-in). Surface
  checks from outside pass: anonymous reads 401, malformed device input 400,
  only the `public` schema exposed, GraphQL and storage closed, notify refuses
  requests without its secret.
- Google Cloud project `dekoi-cyberfish` with Firebase, the Android app and the
  FCM sender, created with OpenTofu (ADR 0009); sign-in clients created in the
  console; all values in GitHub.
- Images `dekoi-canary`, `dekoi-agent`, `dekoi-ids` published, signed and
  public on GHCR.

## Known limitations (MVP)

- Host configuration is frozen when a card is written; images update.
- DeKoi sees traffic to itself and broadcast traffic, not traffic between
  other devices (ADR 0003); a mirror-port option is on the roadmap.
- HTTPS decoy is off until each device has its own certificate.
- IDS rules are at most a week old. On hardware the nftables and ulogd2 scan
  path is verified; a live IDS alert, DHCP and name-poisoning probes are not.
- An ARP sweep from a Wi-Fi client was not detected by a wired DeKoi; many
  access points answer or filter broadcast ARP for Wi-Fi clients. Retest from
  a wired host before relying on sweep detection across Wi-Fi.
- No edge firewall without a custom domain: limits are counted in Postgres
  and only accepted writes count (ADR 0016). A flood from many accounts can
  hold back everyone's pushes for an hour or fill the 500 MB database
  within weeks; monitoring is the alarm.
- Free Supabase plan: no downloadable backups (CI job specified, not yet
  built), sessions never expire, and Supabase may pause a project after a
  week of low activity.

## Roadmap (out of MVP scope)

iOS app; web dashboard; decoy personas selectable in the app; shared household
access and roles beyond owner; email sign-in; custom OS image build; host
configuration as a signed .deb; mirror-port mode for full network inspection;
mDNS advertising of the decoy; realtime updates in the app; Google Play
listing; code-signing certificates for DeKoi Setup; billing; staged fleet
rollouts; multi-region hosting; Cloudflare in front of the API.
