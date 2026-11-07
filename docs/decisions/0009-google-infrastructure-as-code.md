# 0009: Google Cloud and Firebase as code

Status: accepted, 2026-11-07

## Context

Push notifications need Firebase Cloud Messaging and sign-in needs Google
OAuth clients. The owner wants these created as infrastructure as code. The
Google account is personal (no organization) and has no billing account.

## Decision

- OpenTofu (already installed; open-source Terraform) with the `google` and
  `google-beta` providers pinned to exact versions and a committed lock file,
  in `infra/google`. It manages the `dekoi-cyberfish` project, its APIs, the
  Firebase project, the Android app with the release and debug certificate
  fingerprints, and the `fcm-sender` service account with only
  `roles/firebasecloudmessaging.admin`.
- No secret enters the state. The FCM key is created by `publish.sh` and piped
  straight into the `FCM_SERVICE_ACCOUNT` GitHub secret; public values (Firebase
  project, app ID, API key, sender ID, web client ID) go to GitHub variables
  from the OpenTofu outputs.
- The state file stays local and is backed up in the owner's 1Password; it is
  applied from the owner's machine with `gcloud` application default
  credentials.
- The OAuth consent screen and the OAuth clients (web, and Android for release
  and debug) are created in the console: Google offers no API for them on
  projects without an organization, and Firebase does not create them through
  its API. `docs/runbooks/google-firebase.md` lists the exact steps.
- The web client ID is read from Firebase's app config by `publish.sh`, never
  copied by hand, and the backend deploy asks Google to confirm the ID and
  secret belong together before using them. Found in the hardware test: a
  hand-copied Android client ID broke sign-in with error 28444.

## Consequences

- This is the one part of the infrastructure not applied by CI (ADR 0002
  otherwise holds). Moving it into CI needs remote state (a GCS bucket, which
  needs a billing account) and keyless CI access (workload identity
  federation); both are on the roadmap.
- Changes are rare: certificate fingerprints, a new app, key rotation.
- The release and debug certificates are both registered, so debug builds can
  sign in during development and testing.
