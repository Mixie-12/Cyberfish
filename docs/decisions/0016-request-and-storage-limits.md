# 0016: Request and storage limits in Postgres

Status: accepted, 2026-11-08

## Context

Sign-up opens to the public. Without a custom domain there is no firewall
in front of the project (ADR 0001), and anyone may call the device
functions; anyone with a Google account can claim a code they made up and
register a device of their own. The Free plan has a 500 MB database and
500,000 Edge Function calls a month, and every stored event calls `notify`
once. Nothing bounded how often one address or account could write, or how
much it could store.

## Decision

- PostgREST's pre-request function `guard.check_request()` counts every
  write per client address and per signed-in user, in fixed windows, in the
  unlogged table `private.rate_counters`. The limits are the rows of
  `private.request_limits()`: 600 writes a minute per address, 120 per
  user, and per hour 20 registrations per address and 30 claims per address
  or 20 per user. pg_cron prunes ended windows every 10 minutes. The
  backend's own calls (`service_role`) and reads are not counted: PostgREST
  runs reads in a read-only transaction (GET, HEAD, and POST to a stable
  function), where the function returns at once.
- The client address is `CF-Connecting-IP`, which the Supabase platform
  sets on every request (Supabase docs, "Query plans with explain()",
  `supabase.com/docs/guides/api/query-plans`, checked 2026-11-08). The
  first entry of `X-Forwarded-For`, which "Securing your API" shows, is
  whatever the caller sent. IPv6 addresses count by their /64. Without the
  header, which happens only off the platform, the per-address limits do
  not apply; the others do. `private.client_ip()` is the one place that
  reads it.
- Storage limits are triggers, so they hold for every function that
  writes, later versions included: 10 devices per account (setups waiting
  for their DeKoi included, at most 5 of those as before); 3,600 events per
  account and hour, counting those the backend writes from what a device
  sends (new hosts, port forwards, DNS results) and not the offline job's;
  4,096 hosts, 256 networks and 256 port forwards per device; the 10 most
  recently registered push tokens per account, older ones dropped. A setup
  whose DeKoi has not registered after 30 days is removed.
- The events webhook calls `notify` only while the account has made fewer
  than 120 calls and all accounts together fewer than 600 in the hour
  (446,400 in a 31-day month). Events beyond that are stored and shown in
  the app, without a push.
- Every refusal is HTTP 429. Agents since v0.1 keep the batch and retry
  with backoff on any status other than 400, 401 and 403
  (`device/agent/dekoi_agent/api.py`), and the limits sit far above what an
  agent sends (about two writes a minute; repeats from one source are
  folded into one event a minute), so no deployed device changes
  behaviour.
- The OpenAPI description is off, and aggregates and query plans stay off,
  through `pgrst.*` settings on the `authenticator` role.

## Consequences

- A request that fails rolls back its own count, so only accepted writes
  count. Guessing codes and secrets is bounded by their 128 and 256 bits,
  and failed requests by the statement timeouts, not by these limits.
- Counts lock one row per bucket until the transaction commits, so writes
  from one address or one user run one after another, and so do event
  inserts project-wide (the shared notify count). Buckets are always taken
  in the same order: request buckets, the shared notify count, then the
  account's.
- Several customers behind one carrier-grade NAT address share the
  per-address limits; IPv6 avoids that.
- A flood from several accounts can hold back everyone's pushes for the
  rest of an hour; without the gate it would exhaust the function quota and
  get the whole project restricted. Monitoring watches for both
  (`docs/security.md`).
- A device at its host limit fails every inventory report until hosts age
  out after 90 days; no home network comes near 4,096 addresses.
- A determined attacker with many Google accounts and addresses can still
  fill the Free plan's database within weeks. Monitoring of the database
  size is the alarm; a paid plan and a custom domain with a firewall are
  the next steps.
- Every table a device or user can grow needs a per-device or per-account
  limit and a retention when it is added. Health samples and speed tests
  are bounded by time (one row per hour and target, one test per 20
  hours) and need none.
- Rejected: an Edge Function proxy (more code and a function call per
  request), counting failed requests with sequences (one per bucket,
  dynamic SQL), and Cloudflare in front (needs the paid custom domain).
