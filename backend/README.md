# backend

Supabase project: SQL migrations, RLS policies, RPCs, scheduled jobs and their
pgTAP tests. Contracts are described in `docs/architecture.md`.

Requires Docker and the Supabase CLI (version pinned in
`.github/workflows/backend.yml`). Run from this directory:

```
supabase start -x imgproxy,logflare,vector,supavisor,mailpit,studio,edge-runtime,realtime,storage-api
supabase test db
supabase db advisors --type all --fail-on warn
supabase db reset     # reapply all migrations after editing one
supabase stop
```

Edge Function `notify` (Deno version pinned in the same workflow), from
`supabase/functions/notify`:

```
deno fmt --check && deno lint && deno check *.ts && deno test --no-prompt
```
