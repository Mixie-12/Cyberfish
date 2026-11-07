# 0022: Keys in CI, image signing in Cloud KMS, automatic rotation

Status: accepted, 2026-11-07. Amends 0002, 0004, 0007 and 0009.

## Context

A review before public launch found: the image signing key was a GitHub
secret any workflow on any branch could read (F1); pushes to `main` signed
`:edge` images with the key devices trust, in the repositories devices pull
from (F2); the device policy trusted every repository under
`ghcr.io/mixie-12` and had no second key (F3). CI also held long-lived
secrets that nothing rotated: the notify webhook secret, the FCM key and the
database password.

## Decision

- Two GitHub environments. `production` (from `main` only) holds the
  Supabase access token and the Google sign-in secret. `release` (from `v*`
  tags only, the owner approves every run) holds the first cosign key and
  the Android signing key. Every workflow starts with `permissions: {}`.
- Images are signed in Cloud KMS: an HSM P-256 key that cannot be exported,
  used through workload identity federation by `device-images.yml` on a
  `v*` tag in the `release` environment only (`infra/google`). The weekly
  rebuild starts that workflow on the latest release tag and waits for the
  owner like any release.
- Until the owner rewrites the one card in the field, releases are signed
  with both the first key and the KMS key. The first key retires on
  2026-12-31 (repository variable `EXPIRES_COSIGN_PRIVATE_KEY`).
- New cards trust exactly `dekoi-agent`, `dekoi-canary` and `dekoi-ids`,
  each with `keyPaths`: the KMS key and an offline backup key kept in
  1Password. Until both public keys are committed, new cards keep the first
  key. Edge images go unsigned to `dekoi-*-edge`; nothing in the field or
  in the end-to-end test uses them.
- Releases are scanned with Grype before signing and fail on a critical
  vulnerability that has a fix; the weekly security workflow scans
  `:stable`, runs OSV-Scanner on pip and Gradle and the Supabase advisors
  on production. gitleaks runs as a checked binary over the whole history.
- Rotation without people: `rotate-keys.yml` replaces the notify webhook
  secret (function first with the old one still accepted, then Vault) and
  the FCM key (created and deleted through federation) every month. Neither
  is stored in GitHub. `db push` and `db dump` sign in with a temporary
  role that the access token mints, so CI has no database password.
- Credentials that cannot rotate themselves (Supabase access token, first
  cosign key, Google OAuth client secret) have a date in an `EXPIRES_*`
  variable; `expiry.yml` opens an issue 30 days before.
- Nightly backups: a CLI dump encrypted with `age` to a public key in git,
  kept 90 days as an artifact.
- Repository settings are code (`infra/github/apply.sh`).

## Consequences

- The access token needs Database read-write (to mint the login role and
  run the Vault update) and Advisors read; with the database password gone
  it is the one Supabase credential in CI.
- Every release and every weekly rebuild waits for an approval; IDS rules
  are as old as the last approved rebuild. Deviation from `docs/PROMPT.md`
  section 3.5 (automatic weekly rebuild; secrets as repository secrets).
- The webhook rotation relies on notify accepting `NOTIFY_SECRET_PREVIOUS`
  as well as `NOTIFY_SECRET`; until notify on `main` reads it, the job
  refuses to rotate.
- Cloud KMS needs a billing account (about 1 USD a month).
- A card written before the switch keeps trusting the first key and every
  repository under `ghcr.io/mixie-12`; only rewriting it changes that.
- Environments with reviewers, rulesets and private vulnerability reporting
  need the repository to be public, or a paid plan.
- Pull requests are not required: one maintainer cannot approve their own.
