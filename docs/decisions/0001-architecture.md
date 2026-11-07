# 0001: MVP architecture and hosting

Status: accepted, 2026-11-07

## Context

DeKoi needs a backend for auth, device data and alerts, a provisioning path
for the Raspberry Pi, an update path that needs no human, and an Android app.
The owner's goals: least code, fewest moving parts, off-the-shelf components,
strong security, quickest working MVP. `docs/PROMPT.md` section 3 describes the
architecture in full; this record captures the decisions and the owner's
answers from Phase 0.

## Decision

- Backend: Supabase (Postgres with RLS, Auth, PostgREST, pg_cron, one Edge
  Function). No custom API server.
- Hosting: Supabase Cloud, EU region, chosen by the owner over a self-hosted
  homelab deployment. The code is identical either way; moving later is a URL
  and keys.
- Device: official Raspberry Pi OS Lite, cloud-init first boot, OpenCanary for
  decoys, a standard-library Python agent, Podman Quadlets with signed images
  and `podman auto-update` (rollback included), unattended-upgrades for the OS.
- SD card writing: Raspberry Pi Imager CLI driven by DeKoi Setup (Compose
  Desktop). No custom disk writer.
- App: Kotlin, Jetpack Compose, supabase-kt, Sign in with Google, FCM for push.
- Android application ID: `no.cyberfish.dekoi`.
- Repository stays private.

## Consequences

- No servers to patch. The Supabase dashboard is protected by the owner's
  account; 2FA on that account is mandatory.
- Without a custom domain (paid add-on) there is no Cloudflare in front of the
  API, so there is no edge rate limiting. Claim attempts are limited per user in
  SQL; registration and device calls are protected by 128-bit and 256-bit
  secrets. Revisit if abuse appears.
- The Free plan has no downloadable backups. Backups are `supabase db dump`
  runs per the runbook until the owner moves to a paid plan.
- The surface we do not use (GraphQL, Storage, Realtime, extra schemas) is
  switched off or left empty, and the deploy runbook checks it from outside.
- Private repository: CodeQL needs paid GitHub Advanced Security, so it is not
  used; linters, Dependabot and gitleaks remain. Device images are pushed to
  GHCR and the packages are set to public so devices pull without credentials.
- arm64 images are built under QEMU on standard runners, and only on `main` and
  tags, to stay inside the free Actions minutes of a private repository.
