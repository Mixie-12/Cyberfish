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

### Phase 5: Notifications (backend done, app in progress)
Decision function in SQL (coalescing per device and source per 10 minutes,
trusted sources never, device_offline always, device_online never), events
trigger through pg_net with the webhook URL and secret in Vault, `notify`
Edge Function sending FCM HTTP v1 with fixed templates (ADR 0007). 106
pgTAP tests and 8 Deno tests; verified end to end locally against a mock FCM.
The backend deploys without Firebase; pushes start once the service account
secret exists.

### Phase 6: DeKoi Setup (in progress, parallel branch)

### Phase 7: Hardware, hardening, release (not started)

## Owner actions open

1. Supabase bootstrap: `docs/runbooks/deploy-backend.md`, steps 1 to 6.
   Gives CI `SUPABASE_PROJECT_REF`, `SUPABASE_PUBLISHABLE_KEY` (variables) and
   `SUPABASE_ACCESS_TOKEN`, `SUPABASE_DB_PASSWORD` (secrets).
2. Make the GHCR packages public once each exists: `dekoi-canary` now,
   `dekoi-agent` and `dekoi-ids` after their first publish.
3. Google and Firebase (one Google Cloud project, used for nothing else):
   - OAuth consent screen (external, app name DeKoi).
   - OAuth client "Web application": its client ID becomes the repository
     variable `GOOGLE_WEB_CLIENT_ID`, its secret the secret
     `GOOGLE_WEB_CLIENT_SECRET`.
   - OAuth client "Android": package `no.cyberfish.dekoi`, SHA-256 from the
     table above. No secret.
   - Firebase project on the same Google Cloud project, Android app
     `no.cyberfish.dekoi`. Its public values become the repository
     variables `FIREBASE_PROJECT_ID`, `FIREBASE_APP_ID`, `FIREBASE_API_KEY`,
     `FIREBASE_SENDER_ID`. A service account key (Project settings, Service
     accounts, Generate new private key) becomes the secret
     `FCM_SERVICE_ACCOUNT`: `gh secret set FCM_SERVICE_ACCOUNT < key.json`,
     then delete the file.
4. Phase 7 session: a Raspberry Pi 4 or 5, a memory card, a card reader, a
   network cable, an Android phone; about one hour.

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
