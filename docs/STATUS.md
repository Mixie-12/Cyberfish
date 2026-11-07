# Status

Updated 2026-11-07. Code for Phases 0 to 4 and the network detection of ADR
0003 is on `main` and green in CI. Phases 5 (notifications) and 6 (DeKoi
Setup) are being built in parallel branches. Phase 3 (deploy) waits for the
owner's bootstrap; Phase 7 (hardware) for a session with a Pi.

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
| Supabase access token, DB password   | Not created yet (owner action 1)                          |
| Google web client secret, Firebase service account | Not created yet (owner action 3)            |

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

### Phase 3: Deploy (waiting for owner action 1)

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

### Phase 7: Hardware, hardening, release (not started)

## Owner actions open

1. Replace the Supabase token `github-ci-dekoi` with one that also has
   "Add-ons: Read" (`docs/runbooks/deploy-backend.md`, step 4), and store it with
   `gh secret set SUPABASE_ACCESS_TOKEN --repo Mixie-12/Cyberfish`. The first
   deploy already applied all migrations and the notify function; only the
   configuration push (Google sign-in, exposed schemas) waits for this.
2. Google sign-in clients in the console (`docs/runbooks/google-firebase.md`,
   "Sign-in clients"): consent screen, publish, one web and two Android
   clients, then `GOOGLE_WEB_CLIENT_ID` and `GOOGLE_WEB_CLIENT_SECRET`.
3. Make the GHCR packages `dekoi-canary`, `dekoi-agent` and `dekoi-ids` public
   (github.com/Mixie-12?tab=packages, each package, Package settings, Change
   visibility, Public). All three are published and signed.
4. Phase 7 session: a Raspberry Pi 4 or 5, a memory card, a card reader, a
   network cable, an Android phone; about one hour.

Done 2026-11-07: Supabase project `rmllpietnopviodbdkky` deployed by CI;
Google Cloud project `dekoi-cyberfish` with Firebase, the Android app and the
FCM sender created with OpenTofu (ADR 0009); Firebase variables and
`FCM_SERVICE_ACCOUNT` set in GitHub; images published to GHCR as `:edge`.

## Known limitations (MVP)

- Host configuration is frozen when a card is written; images update.
- DeKoi sees traffic to itself and broadcast traffic, not traffic between
  other devices (ADR 0003); a mirror-port option is on the roadmap.
- HTTPS decoy is off until each device has its own certificate.
- IDS rules are at most a week old; a live IDS alert, the nftables and
  ulogd2 path, DHCP and name-poisoning probes are verified on hardware only.
- No edge rate limiting without a Supabase custom domain.
- Free Supabase plan: backups are manual dumps.

## Roadmap (out of MVP scope)

iOS app; web dashboard; decoy personas selectable in the app; shared household
access and roles beyond owner; email sign-in; custom OS image build; host
configuration as a signed .deb; mirror-port mode for full network inspection;
mDNS advertising of the decoy; realtime updates in the app; Google Play
listing; code-signing certificates for DeKoi Setup; billing; staged fleet
rollouts; multi-region hosting; Cloudflare in front of the API.
