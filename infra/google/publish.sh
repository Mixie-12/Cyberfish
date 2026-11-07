#!/bin/sh
# Copies the public values from the OpenTofu outputs into GitHub repository
# variables: Firebase and sign-in for the app and backend, and the identities
# GitHub Actions uses to reach Google without keys (ADR 0009, 0022). No secret
# passes through here; the FCM key is created and handed to the backend by
# the rotate-keys workflow.
set -eu

repo=Mixie-12/Cyberfish
cd "$(dirname "$0")"

output() {
    tofu output -raw "$1"
}

publish() {
    gh variable set "$1" --repo "$repo" --body "$2"
}

publish FIREBASE_PROJECT_ID "$(output firebase_project_id)"
publish FIREBASE_APP_ID "$(output firebase_app_id)"
publish FIREBASE_API_KEY "$(output firebase_api_key)"
publish FIREBASE_SENDER_ID "$(output firebase_sender_id)"
publish FCM_SENDER "$(output fcm_sender_email)"
publish GOOGLE_WIF_ROTATION_PROVIDER "$(output github_rotation_provider)"
publish GOOGLE_FCM_KEY_ROTATOR "$(output fcm_key_rotator_email)"
publish GOOGLE_WIF_RELEASE_PROVIDER "$(output github_release_provider)"

web_client="$(output google_web_client_id)"
if [ -n "$web_client" ]; then
    publish GOOGLE_WEB_CLIENT_ID "$web_client"
else
    echo "No web OAuth client yet; see docs/runbooks/google-firebase.md" >&2
fi

kms_key="$(output cosign_kms_key)"
if [ -n "$kms_key" ]; then
    publish GOOGLE_IMAGE_SIGNER "$(output image_signer_email)"
    publish COSIGN_KMS_KEY "$kms_key"
else
    echo "No KMS signing key (billing_account not set); releases sign with the first key only" >&2
fi
