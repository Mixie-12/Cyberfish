# 0002: GitOps for every environment

Status: accepted, 2026-11-07

## Context

The owner requires that everything is GitOps: no ad hoc changes. The first
brief had backend deployment run by hand from the owner's machine so that CI
would hold no Supabase credentials. That trades one risk (credentials in CI)
for another (production drifting from git, changes nobody can review or
reproduce).

## Decision

- Git is the only source of truth for every running environment. CI is the
  only thing that changes them.
- Backend: a push to `main` that passes the backend tests deploys migrations
  (`supabase db push`), project configuration from `config.toml`
  (`supabase config push`) and Edge Functions (`supabase functions deploy`),
  all with `--remote=<project ref>`. Production-only values are set as
  `SUPABASE_*` variables in the deploy job, with secrets coming from GitHub
  Actions secrets.
- Devices: release tags build, sign and publish images; devices follow
  `:stable` through `podman auto-update`. No one logs in to a device.
- App and DeKoi Setup: release tags build and publish signed artifacts.
- Manual steps are limited to one-time bootstrap (creating the Supabase
  project, creating Firebase and Google OAuth clients, storing secrets in
  GitHub, making GHCR packages public). Each is written down in a runbook.

## Consequences

- CI holds `SUPABASE_ACCESS_TOKEN` and `SUPABASE_DB_PASSWORD`. They are used
  only by the deploy job, which runs only on pushes to `main` after tests pass,
  never on pull requests, with read-only repository permissions and a
  concurrency group so deploys never overlap.
- The access token is scoped to the DeKoi project and to the permissions
  the deploy commands need (scoped tokens, generally available since
  September 2026; list in `docs/runbooks/deploy-backend.md`), with an expiry
  of at most a year. Changing auth settings requires Project Settings
  read-write, which also allows pausing or deleting the project; accepted
  so that sign-in configuration stays in git.
- Branch protection is not available for private repositories on the GitHub
  Free plan, so anyone with write access to the repository can deploy. Today
  that is only the owner. Revisit before adding collaborators.
- The deploy job is skipped until the `SUPABASE_PROJECT_REF` repository
  variable exists, so the pipeline stays green before bootstrap.
- Dashboard changes on the project are drift and get overwritten by the next
  deploy where `config.toml` declares the setting.
