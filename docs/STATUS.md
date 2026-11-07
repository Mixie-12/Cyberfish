# Status

Current phase: 1 (backend schema and security core)

## Owner answers (Phase 0)

| Question             | Answer                                             |
| -------------------- | -------------------------------------------------- |
| Backend hosting      | Supabase Cloud (ADR 0001)                          |
| Repository           | Private; no CodeQL                                 |
| Android app ID       | `no.cyberfish.dekoi`                               |
| License              | Proprietary, no license file (default)             |
| Domain               | Not needed for the MVP on Supabase Cloud           |
| Firebase and Google  | Pending; steps will be provided in Phase 4         |
| Hardware             | Pending; needed in Phase 7                         |

## Development machine

Windows 11. Installed in Phase 0: Temurin JDK 21.0.13, Android SDK
(platform 36, build-tools 36.1.0, platform-tools), Supabase CLI 3.0.0
(`%LOCALAPPDATA%\Programs\supabase`), Raspberry Pi Imager 2.0.8 (ships
`rpi-imager-cli.cmd`). Already present: Docker Desktop 29.9, Python 3.13 and
3.14, uv, Node 24, WSL2 Ubuntu 24.04, gh (authenticated as Mixie-12).

## Phase log

### Phase 0: Bootstrap (done)
- Toolchain installed; downloads verified against published SHA-256.
- Repository skeleton, ADR 0001, gitleaks workflow, Dependabot for Actions.
- `docs/PROMPT.md` updated for Supabase Cloud and the private repository.

## Owner actions open

None right now.

## Roadmap (out of MVP scope)

iOS app; web dashboard; decoy personas selectable in the app; shared household
access and roles beyond owner; email sign-in; custom OS image build; host
configuration as a signed .deb; mDNS advertising of the decoy; realtime updates
in the app; Google Play listing; code-signing certificates for DeKoi Setup;
billing; staged fleet rollouts; multi-region hosting; Cloudflare in front of
the API (needs a Supabase custom domain).
