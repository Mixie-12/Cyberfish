# Rotate keys

| Key                                   | How to rotate                                                                 | Effect on devices |
| ------------------------------------- | ----------------------------------------------------------------------------- | ----------------- |
| Supabase publishable key              | Create a new one in Supabase, update the `SUPABASE_PUBLISHABLE_KEY` repository variable, release (the agent image carries the key), then revoke the old key | none; devices update within a day |
| Supabase secret keys, access token, DB password | Rotate in Supabase, update the GitHub secrets, run the backend workflow | none |
| Firebase service account              | Create a new key, update the function secret through the GitHub secret, deploy, delete the old key | none |
| Notification webhook secret           | `openssl rand -hex 32 \| gh secret set NOTIFY_SECRET`, run the backend workflow; the order below keeps every notification | none |
| cosign signing key                    | Generate a new pair, update `COSIGN_PRIVATE_KEY` and `COSIGN_PASSWORD`, commit the new `device/host/etc/dekoi/cosign.pub`, back up in 1Password | every device must be re-flashed, because the public key is fixed on the card (ADR 0004) |
| Device secret                         | Remove the device in the app and prepare its card again                       | that device only |

Never paste a key into chat or a commit. Use `gh secret set NAME` and type the
value at the prompt.

## Notification webhook secret

The database sends the secret held in Vault (`notify_secret`) with every
event; the `notify` function accepts `NOTIFY_SECRET` and, while a rotation
runs, `NOTIFY_SECRET_PREVIOUS` (both at least 32 characters; an unset or
empty previous secret accepts nothing). The secret Vault sends must be
accepted by the function at every moment, so the deploy job does the
rotation in this order:

1. Read the secret Vault holds now. If it equals `NOTIFY_SECRET`, unset
   `NOTIFY_SECRET_PREVIOUS` (`supabase secrets unset`) and stop.
2. Set the function secrets `NOTIFY_SECRET` (new) and
   `NOTIFY_SECRET_PREVIOUS` (the value from step 1). From now on both are
   accepted.
3. Write the new value to Vault (`vault.update_secret`). New events carry
   it; events already queued in pg_net still carry the old one, which is
   still accepted.
4. On the next deploy, at least 10 minutes later, step 1 finds Vault and
   `NOTIFY_SECRET` equal and removes the previous secret.

Never write Vault before step 2, and never remove the previous secret before
step 3. `supabase db push` writes Vault only when it applies a migration
(ADR 0007), so step 3 must be its own deploy step, not left to `db push`.
