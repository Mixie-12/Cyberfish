# Rotate keys

| Key                                   | How to rotate                                                                 | Effect on devices |
| ------------------------------------- | ----------------------------------------------------------------------------- | ----------------- |
| Supabase publishable key              | Create a new one in Supabase, update the `SUPABASE_PUBLISHABLE_KEY` repository variable, release (the agent image carries the key), then revoke the old key | none; devices update within a day |
| Supabase secret keys, access token, DB password | Rotate in Supabase, update the GitHub secrets, run the backend workflow | none |
| Firebase service account              | Create a new key, update the function secret through the GitHub secret, deploy, delete the old key | none |
| cosign signing key                    | Generate a new pair, update `COSIGN_PRIVATE_KEY` and `COSIGN_PASSWORD`, commit the new `device/host/etc/dekoi/cosign.pub`, back up in 1Password | every device must be re-flashed, because the public key is fixed on the card (ADR 0004) |
| Device secret                         | Remove the device in the app and prepare its card again                       | that device only |

Never paste a key into chat or a commit. Use `gh secret set NAME` and type the
value at the prompt.
