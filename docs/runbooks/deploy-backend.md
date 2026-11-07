# Deploy the backend

The backend is deployed by CI from `main` (ADR 0002): the `deploy` job in
`.github/workflows/backend.yml` applies migrations and `config.toml` to the
Supabase project. Nobody changes the project by hand. This runbook covers the
one-time bootstrap, routine deploys, checks and backups.

## One-time bootstrap (owner)

1. Create a Supabase account used for DeKoi only, and turn on two-factor
   authentication. A CI access token reaches every project of the account, so
   keep nothing else in it.
2. Create a project named `dekoi` in an EU region (Stockholm, `eu-north-1`).
   Let Supabase generate the database password and store it in 1Password.
3. Note the project ref (the subdomain of the project URL) and the
   publishable key (Project Settings, API Keys). Both are public values.
4. Create an access token (Account, Access Tokens) named `github-ci-dekoi`.
5. Store everything in GitHub. Values for secrets are typed at the prompt, so
   they never appear in the shell history or in chat:

   ```
   gh variable set SUPABASE_PROJECT_REF --repo Mixie-12/Cyberfish --body <project ref>
   gh variable set SUPABASE_PUBLISHABLE_KEY --repo Mixie-12/Cyberfish --body <publishable key>
   gh secret set SUPABASE_ACCESS_TOKEN --repo Mixie-12/Cyberfish
   gh secret set SUPABASE_DB_PASSWORD --repo Mixie-12/Cyberfish
   ```

6. Start the first deploy and watch it:

   ```
   gh workflow run backend.yml --repo Mixie-12/Cyberfish --ref main
   gh run watch --repo Mixie-12/Cyberfish
   ```

7. Run the surface checks below.

## Routine deploys

Merge to `main`. A change under `backend/` runs the tests and, when they
pass, deploys. To re-apply the current state (for example after someone
changed a setting in the dashboard), run the workflow manually as in step 6.

## Surface checks (abuse case 9)

Run from any machine outside the project, with `REF` and `KEY` set to the
project ref and publishable key. Every line must print the expected status.

```
API=https://$REF.supabase.co
H="apikey: $KEY"
curl -s -o /dev/null -w "devices  %{http_code} (expect 401)\n" -H "$H" "$API/rest/v1/devices?select=id"
curl -s -o /dev/null -w "events   %{http_code} (expect 401)\n" -H "$H" "$API/rest/v1/events?select=id"
curl -s -o /dev/null -w "register %{http_code} (expect 400)\n" -H "$H" -H "Content-Type: application/json" \
  -d '{"claim_code":"x","secret":"y","agent_version":"z"}' "$API/rest/v1/rpc/device_register_v1"
curl -s -o /dev/null -w "claim    %{http_code} (expect 401)\n" -H "$H" -H "Content-Type: application/json" \
  -d '{"claim_code":"x","name":"y"}' "$API/rest/v1/rpc/claim_device_v1"
curl -s -o /dev/null -w "private  %{http_code} (expect 404 or 406)\n" -H "$H" -H "Accept-Profile: private" \
  "$API/rest/v1/device_credentials"
curl -s -w "\ngraphql  (expect an error, no data)\n" -H "$H" -H "Content-Type: application/json" \
  -d '{"query":"{__typename}"}' "$API/graphql/v1"
curl -s -w "\nbuckets  (expect [] or 4xx)\n" -H "$H" "$API/storage/v1/bucket"
```

Record the output and the date in `docs/STATUS.md`.

## Backups

The Free plan has no downloadable backups. Until the project moves to a paid
plan (decision for the owner before real customers), take a dump after every
deploy that changes data shape, and at least weekly:

```
supabase db dump --remote=$REF -f dekoi-schema.sql
supabase db dump --remote=$REF --data-only -f dekoi-data.sql
```

Store both encrypted (1Password document or the homelab backup), never in git.

Restore test, into the local stack:

```
cd backend
supabase start
psql "postgresql://postgres:postgres@127.0.0.1:54322/postgres" -f dekoi-data.sql
supabase test db
```
