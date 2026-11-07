# Backups and restore

Every night `.github/workflows/backup.yml` dumps production with the
Supabase CLI (custom roles, schema, and data including `auth.users`; Vault is
not included), encrypts the dumps with `age` to the public key in
`backend/backup-recipients.txt`, and keeps the result as a workflow artifact
for 90 days. Artifacts of a public repository can be downloaded by anyone
with a GitHub account; only the private key in 1Password opens them.

## Bootstrap the key (owner, once)

On the owner's machine, with age checked against its published SHA-256:

```
cd "$(mktemp -d)"
curl -fsSLO https://github.com/FiloSottile/age/releases/download/v1.3.2/age-v1.3.2-windows-amd64.zip
echo "f48d8f8f9ebe903ab5027ed067652f2cc1db94bc206976430133b905dcd8e8c7  age-v1.3.2-windows-amd64.zip" | sha256sum -c -
unzip -q age-v1.3.2-windows-amd64.zip
./age/age-keygen.exe -o dekoi-backup.key
op document create dekoi-backup.key --title "DeKoi backup age key" --vault Encryption
./age/age-keygen.exe -y dekoi-backup.key > <repository>/backend/backup-recipients.txt
```

Delete the directory, then commit `backend/backup-recipients.txt` and push.
Until the file exists, the backup workflow fails every night. Keep the
unzipped `age` for restores.

## Get a backup

From the run's page (Actions, backup), or:

```
gh run list --repo Mixie-12/Cyberfish --workflow backup.yml --limit 5
gh run download <run id> --repo Mixie-12/Cyberfish
op document get "DeKoi backup age key" --vault Encryption --out-file dekoi-backup.key
age -d -i dekoi-backup.key dekoi-<date>/dekoi-<date>.tar.age | tar -x
rm dekoi-backup.key
```

This gives `roles.sql`, `schema.sql` and `data.sql`. They hold owners' data:
keep them on an encrypted disk and delete them when done.

## Test a restore (once, and after schema changes)

Into the local stack, which applies the migrations itself:

```
cd backend
supabase start
psql "postgresql://postgres:postgres@127.0.0.1:54322/postgres" --single-transaction \
  -v ON_ERROR_STOP=1 -c "set session_replication_role = replica" -f data.sql
supabase test db
```

The tests must pass and the row counts must match the dump.

## Restore production

Restoring overwrites production; the owner decides when.

1. Create a fresh Supabase project or reset the existing one.
2. Point `SUPABASE_PROJECT_REF` at it and run the backend workflow, so CI
   applies every migration and the configuration. Then run `rotate-keys.yml`
   so notify and Vault get their secrets.
3. Load the roles and the data:

   ```
   psql "<connection string from the Supabase dashboard>" --single-transaction \
     -v ON_ERROR_STOP=1 -f roles.sql -c "set session_replication_role = replica" -f data.sql
   ```

4. Run the surface checks in `deploy-backend.md`.

If the project ref changed, release a new agent image (it carries the URL);
devices follow within a day.
