# Rotate keys

Every credential, where it lives and how it is replaced (ADR 0022). Never
paste a key into chat or a commit: `gh secret set NAME --env ENV` reads the
value at the prompt.

| Credential | Lives in | Replaced | Effect on devices |
| ---------- | -------- | -------- | ----------------- |
| Notify webhook secret | Vault and the `notify` function | monthly by `rotate-keys.yml` | none |
| FCM sender key | the `notify` function | monthly by `rotate-keys.yml` | none |
| Supabase access token | `production` secret `SUPABASE_ACCESS_TOKEN` | by hand before it expires | none |
| Google web client secret | `production` secret `GOOGLE_WEB_CLIENT_SECRET` | by hand, yearly | none |
| Supabase database password | 1Password only; CI does not use it | by hand if exposed | none |
| Supabase publishable key | variable `SUPABASE_PUBLISHABLE_KEY` | by hand | the agent image carries it; devices follow within a day |
| Image signing key (Cloud KMS) | an HSM in `dekoi-cyberfish`, never exported | never; see "Image signing keys" | new key means new cards |
| Image signing backup key | 1Password | never; used only if the KMS key is lost | as above |
| First cosign key | `release` secrets `COSIGN_PRIVATE_KEY`, `COSIGN_PASSWORD`; 1Password | retires 2026-12-31 | the card written before the switch |
| Android signing key | `release` secrets `ANDROID_KEYSTORE_*`; 1Password | never (it is the app's identity) | none |
| Backup decryption key | 1Password; public key in `backend/backup-recipients.txt` | by hand if exposed | none |
| Device secret | the device and a hash in the backend | remove the device in the app, prepare its card again | that device only |

Credentials replaced by hand have their date in a repository variable
`EXPIRES_<secret>` (YYYY-MM-DD); `expiry.yml` opens an issue 30 days before.

## Notify webhook secret and FCM key (automatic)

`rotate-keys.yml` runs on the first of every month, and on demand:

```
gh workflow run rotate-keys.yml --repo Mixie-12/Cyberfish --ref main
```

Webhook secret: a new random secret becomes `NOTIFY_SECRET` and the old one
`NOTIFY_SECRET_PREVIOUS`; after the function workers have restarted, the job
checks that notify accepts the new one, writes it to Vault, and removes the
old one. If a step fails, notify still accepts what Vault sends; run the
workflow again.

FCM key: the job creates a key for `fcm-sender` through workload identity
federation, proves it by minting a token with it, gives it to notify, and
after the workers have restarted deletes every older key.

## Supabase access token

1. Create a token as in `deploy-backend.md`, step 4, with an expiry of one
   year at most.
2. `gh secret set SUPABASE_ACCESS_TOKEN --env production --repo Mixie-12/Cyberfish`
3. `gh workflow run backend.yml --repo Mixie-12/Cyberfish --ref main` and
   check that the deploy is green.
4. Revoke the old token (Account, Access Tokens).
5. `gh variable set EXPIRES_SUPABASE_ACCESS_TOKEN --repo Mixie-12/Cyberfish --body <expiry date>`

## Google web client secret

1. Google Cloud console, Clients, "DeKoi backend (Supabase)", Add secret.
2. `gh secret set GOOGLE_WEB_CLIENT_SECRET --env production --repo Mixie-12/Cyberfish`
3. Run the backend workflow as above; the deploy checks the pair with Google.
4. Disable, then delete, the old secret in the console.
5. `gh variable set EXPIRES_GOOGLE_WEB_CLIENT_SECRET --repo Mixie-12/Cyberfish --body <date a year ahead>`

## Image signing keys

A card trusts the keys written onto it and nothing else; host configuration
never changes after the card is written. Only cards written after a key
change trust the new key.

### Switch new cards to the Cloud KMS key (once)

1. Billing and the key: see `google-firebase.md`, Cloud KMS. After
   `tofu apply`, write the public key:

   ```
   cd infra/google
   tofu output -raw image_signing_public_key > ../../device/host/etc/dekoi/image-signing.pub
   ./publish.sh
   ```

2. Backup key, on the owner's machine, with the cosign the release workflow
   uses. Use a long password from the 1Password generator at the prompt:

   ```
   cd "$(mktemp -d)"
   curl -fsSLO https://github.com/sigstore/cosign/releases/download/v3.1.4/cosign-windows-amd64.exe
   echo "cbe953787074611f1334cbf37dc1cf953995bb8c45398eb4fc7bb781d29c1f2a  cosign-windows-amd64.exe" | sha256sum -c -
   ./cosign-windows-amd64.exe generate-key-pair --output-key-prefix image-signing-backup
   op document create image-signing-backup.key --title "DeKoi image signing backup key" --vault Encryption
   ```

   Store the password in the same 1Password item, copy
   `image-signing-backup.pub` to `device/host/etc/dekoi/`, and delete the
   directory.

3. In `device/host/etc/containers/policy.json` set, for all three
   repositories:

   ```
   "keyPaths": ["/etc/dekoi/image-signing.pub", "/etc/dekoi/image-signing-backup.pub"]
   ```

   Add both files to `write_files` in `device/cloud-init/user-data.yaml`,
   like `cosign.pub`. Keep `cosign.pub` on disk until the first key retires:
   the release workflow checks the first signature against it.
4. `pytest` in `device/` and the `signatures` job prove the policy and the
   files agree. Commit, push, and tag a release: it carries both signatures.
5. Write new cards from then on.

### Retire the first key (by 2026-12-31)

After the card written before the switch has been rewritten:

1. Delete the step "Sign with the first key" from `device-images.yml`,
   `device/host/etc/dekoi/cosign.pub` and its `write_files` entry.
2. `gh secret delete COSIGN_PRIVATE_KEY --env release`, the same for
   `COSIGN_PASSWORD`, and `gh variable delete EXPIRES_COSIGN_PRIVATE_KEY`.
3. Delete the 1Password item "DeKoi cosign image signing key".

### If the KMS key is lost or exposed

Devices trust the backup key, so updates can continue: store the backup
key and password as `release` secrets `COSIGN_PRIVATE_KEY` and
`COSIGN_PASSWORD`, point the first-key step at `image-signing-backup.pub`,
and release. Disable the KMS key version in Google Cloud. Devices keep
trusting an exposed key until their cards are rewritten.

### Take back a signed image

A device checks the signature on every pull. Deleting the signature from
GHCR stops devices from accepting that image again; devices already running
it keep it until `:stable` moves. Use this for a vulnerable release, and
once for the `:edge` images that `main` signed with the first key before
ADR 0022 (they are in the release repositories, and the old card accepts
any of them):

```
gh auth refresh -s read:packages,delete:packages
gh api "user/packages/container/dekoi-agent/versions" --paginate \
  --jq '.[] | [.id, (.metadata.container.tags | join(" "))] | @tsv'
gh api --method DELETE "user/packages/container/dekoi-agent/versions/<id>"
```

A signature is the version tagged `sha256-<digest>.sig`. Keep the versions
that `:stable` and the release tags point at (`git rev-list -n 1 <tag>` is
the revision tag) and their signatures; delete the rest, in all three
repositories.
