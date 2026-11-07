# 0027: Daily read-only checks of production

Status: accepted, 2026-11-07

## Context

`docs/security.md`, "Monitoring", listed checks against production that
nothing ran. Several controls fail silently: the per-address limits, the
public address, the country and the Wi-Fi radio switch off if the platform
stops passing `cf-connecting-ip` or `cf-ipcountry` to the database; a
flood shows only as a growing database or a closed notify gate. The CI
token has Database read but not yet Advisors read (owner action 1).

## Decision

- `.github/workflows/monitor.yml` runs every morning, one job per kind of
  check, so one failure does not hide another. Any failure opens the issue
  "Production monitoring failed", or comments on the open one, with the
  failed job names and the run link. Logs hold names, counts and booleans.
- Database checks are one static query, `backend/monitor.sql`, sent to
  the Management API's read-only endpoint, which runs it as
  `supabase_read_only_user` (reads everything, bypasses RLS, cannot
  write). Each row is a count and its maximum. The expected cron jobs are
  read from the migrations, not copied.
- `public.self_check_v1()` lets the workflow ask, through the public API
  with the publishable key, whether the two headers arrived. It returns two
  booleans, never the values; anon only.
- The advisors move from the weekly security workflow to this daily one.

## Consequences

- No new credential: everything but the advisors works with the token in
  use. The config job fails until Realtime is suspended (owner action 3),
  the advisors job until the new token is set.
- `self_check_v1` is stable, so PostgREST runs it read-only and the
  request limits do not count it: reads cannot be counted (ADR 0016). A
  volatile version would be counted on POST but not on GET (tried through
  the local gateway). It reads no table.
- A new weekly job alarms until its first run; a job on given days of the
  month needs the period rule in `monitor.sql` extended.
- The hosted advisors may report the lints for security definer functions
  that anon or authenticated can run (splinter 0028, 0029): every device
  and app RPC is one by design, and `00_hardening` asserts their exact
  grants. The local CLI does not report them. If production does, the
  owner decides whether to ignore them by name.
- Not built: the surface checks of `deploy-backend.md` and the restore
  test; the token's expiry stays a calendar reminder.
