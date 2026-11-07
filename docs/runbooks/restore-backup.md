# Back up and restore

The Free plan has no downloadable backups and no point-in-time recovery
(Pro keeps daily backups for 7 days; PITR is a paid add-on). Until then CI
takes the backups, and only the owner holds the key that opens them.

## Backup (scheduled CI job)

What the job does, for whoever builds it. It runs daily, and in the deploy
job before `supabase db push` applies a migration. It holds
`SUPABASE_ACCESS_TOKEN` and `SUPABASE_DB_PASSWORD` like the deploy job, and
the repository variable `BACKUP_AGE_RECIPIENT`: the public half of an age
key. The private half exists only in 1Password ("DeKoi
backup age key"), never in GitHub. age comes from the Ubuntu archive
(`apt-get install age`, signature-checked by apt).

1. Dump the schema and the data:

   ```
   supabase db dump --remote="$PROJECT_REF" -f schema.sql
   supabase db dump --remote="$PROJECT_REF" --data-only --use-copy -f data.sql \
     -x private.rate_counters -x cron.job -x cron.job_run_details \
     -x net.http_request_queue -x net._http_response
   ```

   The data dump already leaves out Vault (its values are encrypted with the
   project's root key, which no dump carries; the deploy writes them again,
   `docs/runbooks/rotate-keys.md`) and the migration history. Excluded on
   top: rate counts, which live for minutes; the cron jobs, which the
   migrations create; job and HTTP logs.

2. Prove the dump restores before keeping it: start the local stack from
   `backend/` (it applies every migration), load `data.sql` as in
   "Restore", step 4, against
   `postgresql://postgres:postgres@127.0.0.1:54322/postgres`, and compare
   the row counts of `auth.users`, `public.devices`, `public.events` and
   `public.network_hosts` with the project's. `schema.sql` is kept for
   comparison only: the schema comes from the migrations.
3. Compress, encrypt to the recipient, and remove the plain files:

   ```
   tar -czf - schema.sql data.sql | age -r "$BACKUP_AGE_RECIPIENT" -o "dekoi-$(date -u +%Y%m%dT%H%MZ).tar.gz.age"
   rm schema.sql data.sql
   ```

4. Upload the `.age` file as a workflow artifact with `retention-days: 30`.
   Backups hold personal data (`docs/security.md`, "Personal data"): a
   deleted account is gone from every backup after 30 days.

If any step fails, the job fails and GitHub emails the owner.

Checked on the local stack on 2026-11-08: steps 1 and 2 and the restore
below (dump, reset, load with triggers off): every row came back, the
restored device authenticated, no event was posted to `notify` again, and
the event id sequence continued. age was not run locally.

## Restore

Restoring overwrites production; the owner decides when.

1. Download the artifact and decrypt it on the owner's machine, with the
   key in a temporary file that is deleted afterwards:

   ```
   gh run download <run id> --repo Mixie-12/Cyberfish --name <artifact>
   age -d -i <key file> dekoi-<time>.tar.gz.age | tar -xzf -
   ```

2. Test it locally first: from `backend/`, `supabase start`, then step 4
   against the local database, and compare the row counts.
3. Production: create a new project (or reset the existing one), point
   `SUPABASE_PROJECT_REF` at it and run the backend workflow, so CI applies
   every migration, the configuration and the secrets.
4. Load the data in one transaction with triggers off, so restored events
   neither call `notify` again nor count against today's limits:

   ```
   psql --single-transaction --variable ON_ERROR_STOP=1 \
     --command 'set session_replication_role = replica' \
     --file data.sql --dbname "<connection string>"
   ```

5. Run the surface checks in `deploy-backend.md`.

If the project ref changed, release a new agent image (it carries the URL);
devices follow within a day. Phones keep working once a new app release
carries the new URL; until then owners sign in again.
