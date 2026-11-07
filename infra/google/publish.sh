#!/bin/sh
# Copies the public Firebase and sign-in values from the OpenTofu outputs into
# GitHub repository variables. With --new-fcm-key, also creates a fresh key
# for the FCM sender and pipes it straight into the FCM_SERVICE_ACCOUNT secret;
# the key never lands in the state or in the repository (ADR 0009).
set -eu

repo=Mixie-12/Cyberfish
gcloud="${GCLOUD:-gcloud}"
cd "$(dirname "$0")"

output() {
    tofu output -raw "$1"
}

gh variable set FIREBASE_PROJECT_ID --repo "$repo" --body "$(output firebase_project_id)"
gh variable set FIREBASE_APP_ID --repo "$repo" --body "$(output firebase_app_id)"
gh variable set FIREBASE_API_KEY --repo "$repo" --body "$(output firebase_api_key)"
gh variable set FIREBASE_SENDER_ID --repo "$repo" --body "$(output firebase_sender_id)"

web_client="$(output google_web_client_id)"
if [ -n "$web_client" ]; then
    gh variable set GOOGLE_WEB_CLIENT_ID --repo "$repo" --body "$web_client"
else
    echo "No web OAuth client yet; see docs/runbooks/google-firebase.md" >&2
fi

if [ "${1:-}" = "--new-fcm-key" ]; then
    key="$(mktemp)"
    trap 'rm -f "$key"' EXIT
    "$gcloud" iam service-accounts keys create "$key" \
        --iam-account "$(output fcm_sender_email)" \
        --project "$(output firebase_project_id)" --quiet
    gh secret set FCM_SERVICE_ACCOUNT --repo "$repo" < "$key"
    echo "FCM_SERVICE_ACCOUNT updated; delete older keys of $(output fcm_sender_email)"
fi
