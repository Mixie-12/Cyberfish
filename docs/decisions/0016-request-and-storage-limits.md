# 0016: Request and storage limits in Postgres

Status: accepted, 2026-11-08

## Context

Sign-up opens to the public. Without a custom domain there is no firewall
in front of the project (ADR 0001), and anyone may call the device
functions. Nothing bounded how often one address or one account could
write.

## Decision

- PostgREST's pre-request function `guard.check_request()` counts every
  write per client address and per signed-in user, in fixed windows, in the
  unlogged table `private.rate_counters`. The limits are the rows of
  `private.request_limits()`: 600 writes a minute per address, 120 per
  user, and per hour 20 registrations per address and 30 claims per address
  or 20 per user. A request over a limit gets HTTP 429. pg_cron prunes
  ended windows every 10 minutes. The backend's own calls (`service_role`)
  and reads are not counted: PostgREST runs reads in a read-only
  transaction (GET, HEAD, and POST to a stable function), where the
  function returns at once.
- The client address is `CF-Connecting-IP`, which the Supabase platform
  sets on every request (Supabase docs, "Query plans with explain()",
  `supabase.com/docs/guides/api/query-plans`, checked 2026-11-08). The first entry of `X-Forwarded-For`, which
  "Securing your API" shows, is whatever the caller sent. IPv6 addresses
  count by their /64. Without the header, which happens only off the
  platform, the per-address limits do not apply; the others do.
  `private.client_ip()` is the one place that reads it.
- Every refusal is HTTP 429. Agents since v0.1 keep the batch and retry
  with backoff on any status other than 400, 401 and 403
  (`device/agent/dekoi_agent/api.py`), and the limits sit far above what an
  agent sends (about two writes a minute), so no deployed device changes
  behaviour.
- The OpenAPI description is off, and aggregates and query plans stay off,
  through `pgrst.*` settings on the `authenticator` role.

## Consequences

- A request that fails rolls back its own count, so only accepted writes
  count. Guessing codes and secrets is bounded by their 128 and 256 bits,
  and failed requests by the statement timeouts, not by these limits.
- Counting locks one row per bucket until the request commits, so writes
  from one address or one user run one after another.
- Several customers behind one carrier-grade NAT address share the
  per-address limits; IPv6 avoids that.
- Rejected: an Edge Function proxy (more code and a function call per
  request), counting failed requests with sequences (one per bucket,
  dynamic SQL), and Cloudflare in front (needs the paid custom domain).
