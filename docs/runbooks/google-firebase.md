# Google Cloud and Firebase

Everything Google offers as an API is code in `infra/google` (ADR 0009): the
`dekoi-cyberfish` project, its APIs, Firebase, the Android app with its signing
certificates, and the `fcm-sender` service account. Sign-in clients and the
consent screen exist only in the Google Cloud console for personal accounts, so
they are the manual part below.

## Apply changes (owner's machine)

```
gcloud auth login --update-adc          # once per machine
cd infra/google
op document get "DeKoi OpenTofu state (infra/google)" --vault Encryption --out-file terraform.tfstate
tofu plan                               # read it
tofu apply
./publish.sh                            # copies the public values to GitHub variables
op document edit "DeKoi OpenTofu state (infra/google)" terraform.tfstate --vault Encryption
```

Rotate the FCM sender key with `./publish.sh --new-fcm-key`, then delete the
older key in the console (IAM, Service accounts, fcm-sender, Keys).

## Sign-in clients (console, one time)

In https://console.cloud.google.com/auth/overview?project=dekoi-cyberfish:

1. **Get started.** App name `DeKoi`, your support email, audience
   **External**, your contact email. Agree and create.
2. **Audience.** Publish the app ("In production"). DeKoi asks only for the
   basic profile, so Google does not require a review; without publishing, only
   listed test users can sign in.
3. **Clients, Create client, Web application**, name `DeKoi backend
   (Supabase)`. Create, then store its secret right away (paste it at the
   prompt):

   ```
   gh secret set GOOGLE_WEB_CLIENT_SECRET --repo Mixie-12/Cyberfish
   ```

4. **Clients, Create client, Android**, name `DeKoi release`, package
   `no.cyberfish.dekoi`, SHA-1
   `33:4B:2B:92:43:51:8C:19:C1:DB:EE:25:50:45:38:57:F3:9F:C9:77`.
5. **Clients, Create client, Android**, name `DeKoi debug`, package
   `no.cyberfish.dekoi`, SHA-1
   `6D:EF:24:98:D6:E7:AE:43:B6:C9:63:66:9F:6B:CE:67:89:F9:F4:C5`.
6. `tofu apply`, then `./publish.sh` (see above). It reads the web client ID
   from Firebase and sets `GOOGLE_WEB_CLIENT_ID`; never copy the ID by hand.
   An Android client ID there makes sign-in fail on the phone with error 28444.

The next backend deploy checks the ID and secret with Google, then turns on
Google sign-in in Supabase; the next release builds the app with the ID.
