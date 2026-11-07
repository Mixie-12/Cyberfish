# Status

Current phase: 2 (device stack), finishing; Phase 3 waits for owner actions.

## Owner answers (Phase 0)

| Question             | Answer                                             |
| -------------------- | -------------------------------------------------- |
| Backend hosting      | Supabase Cloud (ADR 0001)                          |
| Deployment           | GitOps: CI changes every environment (ADR 0002)    |
| Repository           | Private; no CodeQL                                 |
| Android app ID       | `no.cyberfish.dekoi`                               |
| License              | Proprietary, no license file (default)             |
| Domain               | Not needed for the MVP on Supabase Cloud           |
| Image signing key    | Generated 2026-11-07; see "Secrets" below          |
| Firebase and Google  | Pending; steps will be provided in Phase 4         |
| Hardware             | Pending; needed in Phase 7                         |

## Secrets and where they live

| Secret                          | Where                                                    |
| ------------------------------- | -------------------------------------------------------- |
| cosign private key and password | GitHub Actions secrets `COSIGN_PRIVATE_KEY`, `COSIGN_PASSWORD`; backup in the owner's 1Password, vault "Encryption", item "DeKoi cosign image signing key" |
| cosign public key               | `device/host/etc/dekoi/cosign.pub` (on every card)       |
| Supabase access token, DB password | Not created yet (Phase 3 bootstrap)                   |

## Development machine

Windows 11. Installed in Phase 0: Temurin JDK 21.0.13, Android SDK
(platform 36, build-tools 36.1.0, platform-tools), Supabase CLI 3.0.0
(`%LOCALAPPDATA%\Programs\supabase`), Raspberry Pi Imager 2.0.8 (ships
`rpi-imager-cli.cmd`). Already present: Docker Desktop 29.9, Python 3.13 and
3.14, uv, Node 24, WSL2 Ubuntu 24.04, gh (authenticated as Mixie-12), 1Password
CLI 2.41.

## Phase log

### Phase 0: Bootstrap (done)
- Toolchain installed; downloads verified against published SHA-256.
- Repository skeleton, ADR 0001, gitleaks workflow, Dependabot for Actions.

### Phase 1: Backend schema and security core (done)
- Five migrations: default privileges revoked, schema with RLS, device API v1,
  app API v1, pg_cron jobs. Secrets and push tokens in a private schema.
- 78 pgTAP tests cover abuse cases 1 to 5 and the exact grants of every API
  role. Supabase advisors report nothing at warn level. Green locally and in
  CI. The deploy job (ADR 0002) is in place and skipped until bootstrap.
- Verified through the real local gateway: claim, register, 403 for a refused
  registration, 400 for malformed input.

### Phase 2: Device stack (in progress)
- Done: agent (33 unit and consistency tests), canary and agent images,
  host configuration, cloud-init template, image signing key, CI checks,
  end-to-end harness, signature enforcement test, image publishing workflow.
- Smoke test found and fixed: OpenCanary rejects MySQL banners not starting
  with 3 to 6; SSH host keys need a writable volume; UserNS=auto needs
  subordinate ID ranges that Debian does not create.
- Open: end-to-end and signature jobs green in CI.

## Owner actions open

1. Bootstrap Supabase Cloud (Phase 3): create the project and store its ref
   and keys in GitHub. Exact steps will be in
   `docs/runbooks/deploy-backend.md`.
2. After the first image publish: make the GHCR packages `dekoi-canary` and
   `dekoi-agent` public (GitHub, Packages, package settings), so devices can
   pull without credentials.

## Known limitations (MVP)

- Host configuration is frozen when a card is written; only images update.
- HTTPS decoy is off until each device can have its own certificate.
- nftables and ulogd2 port-scan path is verified on hardware, not in CI.
- Outbound traffic from the device is not restricted.

## Roadmap (out of MVP scope)

iOS app; web dashboard; decoy personas selectable in the app; shared household
access and roles beyond owner; email sign-in; custom OS image build; host
configuration as a signed .deb; mDNS advertising of the decoy; realtime updates
in the app; Google Play listing; code-signing certificates for DeKoi Setup;
billing; staged fleet rollouts; multi-region hosting; Cloudflare in front of
the API (needs a Supabase custom domain).
