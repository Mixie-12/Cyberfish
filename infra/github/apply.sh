#!/usr/bin/env bash
# Repository settings as code (ADR 0022). Idempotent: run it after changing
# this file, signed in to gh as the repository owner. Rulesets, environment
# reviewers and private vulnerability reporting need a public repository (or
# a paid plan for a private one); GitHub answers 403 or 422 otherwise.
set -euo pipefail

repo=Mixie-12/Cyberfish
owner_id="$(gh api "users/${repo%%/*}" --jq .id)"

# Method and path; the JSON body on stdin.
send() { gh api --method "$1" "repos/$repo/$2" --input - > /dev/null; }

# Creates or replaces the ruleset named $1 with the JSON on stdin.
ruleset() {
    local id
    id="$(gh api "repos/$repo/rulesets" --jq ".[] | select(.name == \"$1\") | .id")"
    if [ -n "$id" ]; then send PUT "rulesets/$id"; else send POST rulesets; fi
}

# Creates or replaces environment $1 with the JSON on stdin, deployable only
# from the one ref pattern $2 of type $3 (branch or tag).
environment() {
    send PUT "environments/$1"
    local policies="environments/$1/deployment-branch-policies" found="" id name type
    while read -r id name type; do
        if [ "$name" = "$2" ] && [ "$type" = "$3" ]; then found=1; continue; fi
        gh api --method DELETE "repos/$repo/$policies/$id"
    done < <(gh api "repos/$repo/$policies" --jq '.branch_policies[] | "\(.id) \(.name) \(.type)"')
    if [ -z "$found" ]; then
        printf '{"name": "%s", "type": "%s"}' "$2" "$3" | send POST "$policies"
    fi
}

# No force-push or deletion of main or of release tags; a release tag never moves.
ruleset main <<'JSON'
{"name": "main", "target": "branch", "enforcement": "active", "bypass_actors": [],
 "conditions": {"ref_name": {"include": ["~DEFAULT_BRANCH"], "exclude": []}},
 "rules": [{"type": "deletion"}, {"type": "non_fast_forward"}]}
JSON
ruleset "release tags" <<'JSON'
{"name": "release tags", "target": "tag", "enforcement": "active", "bypass_actors": [],
 "conditions": {"ref_name": {"include": ["refs/tags/v*"], "exclude": []}},
 "rules": [{"type": "deletion"}, {"type": "non_fast_forward"}, {"type": "update"}]}
JSON

# production: Supabase deploys, rotation, backups and advisors, from main only.
# release: signing images and the APK, from v* tags only, after the owner approves.
environment production main branch <<'JSON'
{"deployment_branch_policy": {"protected_branches": false, "custom_branch_policies": true}}
JSON
environment release 'v*' tag <<JSON
{"reviewers": [{"type": "User", "id": $owner_id}], "prevent_self_review": false,
 "can_admins_bypass": false,
 "deployment_branch_policy": {"protected_branches": false, "custom_branch_policies": true}}
JSON

for feature in vulnerability-alerts automated-security-fixes private-vulnerability-reporting; do
    gh api --method PUT "repos/$repo/$feature" > /dev/null
done
gh api --method PATCH "repos/$repo" --input - > /dev/null <<'JSON'
{"security_and_analysis": {"secret_scanning": {"status": "enabled"},
                           "secret_scanning_push_protection": {"status": "enabled"}}}
JSON
send PUT actions/permissions <<'JSON'
{"enabled": true, "allowed_actions": "all", "sha_pinning_required": true}
JSON
send PUT actions/permissions/workflow <<'JSON'
{"default_workflow_permissions": "read", "can_approve_pull_request_reviews": false}
JSON
