# 0007: Push notifications

Status: accepted, 2026-11-07

## Context

Phase 5: a push within 60 seconds of an event, coalesced, never for trusted
sources, never with attacker-controlled text, and deployed by CI only
(ADR 0002).

## Decision

- The decision is one SQL function, `notification_targets_v1(event_id)`,
  executable only by `service_role`. It returns the owner's push tokens and
  the template key (the event kind), or nothing, and records the push in
  `private.notified_sources`, so coalescing survives restarts and concurrent
  calls. The Edge Function `notify` only sends.
- The webhook is a trigger calling `net.http_post` (pg_net) defined in a
  migration, not the dashboard's Database Webhooks, which create their
  trigger outside git. Its body is the event id only.
- The webhook URL and shared secret live in Vault, declared in `[db.vault]`
  of `config.toml` and filled from CI variables by `supabase db push`.
  Without them, as in tests and local runs, the trigger sends nothing.
- `notify` checks the shared secret itself; gateway JWT verification is off
  for this function only. It calls PostgREST with the service role key and
  FCM HTTP v1 with an OAuth token from a service account JSON (function
  secret), signing the RS256 JWT with WebCrypto. No dependencies.
- Notification text is a fixed template per event kind. The FCM data
  payload is the event id; the app loads the details after sign-in.

## Consequences

- Supabase CLI 3.0.0 writes Vault secrets only when `db push` applies a
  migration, so a changed `NOTIFY_SECRET` takes effect with the next
  migration (runbook, deploy-backend).
- Each event costs one HTTP call to the function, also when it does not
  notify; the agent already collapses repeats, so the volume is small.
- The OAuth token is fetched per notifying event, not cached; acceptable at
  this volume and avoids state in the function.
- firebase-messaging 26 deprecates registration tokens in favour of
  registration by Firebase installation ID (`register()`, `onRegistered()`).
  The app and `notify` still use tokens, which work today; they move to
  installation IDs together, in one tested change.
- The app initialises Firebase from Gradle properties (no
  google-services.json, no plugin). Debug builds without them run with push
  off; release builds require them.
- The FCM project is a one-time owner bootstrap step: a Firebase project and
  a service account key stored as a GitHub secret.
