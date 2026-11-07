# Restore a backup

Backups are taken as described in `deploy-backend.md`, "Backups".

## Test a restore (do this once, and after schema changes)

```
cd backend
supabase start
psql "postgresql://postgres:postgres@127.0.0.1:54322/postgres" -f dekoi-data.sql
supabase test db
```

The tests must pass and the row counts must match the dump.

## Restore production

Restoring overwrites production; the owner decides when.

1. Create a fresh Supabase project or reset the existing one.
2. Point `SUPABASE_PROJECT_REF` at it and run the backend workflow, so CI
   applies every migration and the configuration.
3. Load the data dump:

   ```
   psql "<connection string from the Supabase dashboard>" -f dekoi-data.sql
   ```

4. Run the surface checks in `deploy-backend.md`.

If the project ref changed, release a new agent image (it carries the URL);
devices follow within a day.
