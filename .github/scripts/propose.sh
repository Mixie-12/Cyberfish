#!/usr/bin/env bash
# propose.sh NAME PATH TITLE BODY
# Proposes PATH, as it is in the working tree, in a pull request from the
# branch automation/NAME that auto-merge.yml merges once its checks pass (ADR
# 0036). The branch is the checked-out commit plus one commit made through
# the API, so GitHub signs it. A pull request opened with GITHUB_TOKEN starts
# its workflows only after an approval, so the workflows it would start are
# started on the branch here. Nothing happens while an open pull request
# already proposes the same content. Needs GH_TOKEN (actions, contents and
# pull-requests: write) and GH_REPO; run from the repository root.
set -euo pipefail

name="$1" path="$2" title="$3" body="$4"
branch="automation/$name"
api="repos/$GH_REPO"
content="$(base64 -w 0 "$path")"

open="$(gh api "$api/pulls?state=open&base=main&head=${GH_REPO%%/*}:$branch" --jq '.[0].number // empty')"
if [ -n "$open" ]; then
    proposed="$(gh api "$api/contents/$path?ref=$branch" --jq .content | tr -d '\n')"
    if [ "$proposed" = "$content" ]; then
        echo "#$open already proposes this $path"
        exit 0
    fi
fi

base="$(git rev-parse HEAD)"
exists="$(gh api "$api/git/matching-refs/heads/$branch" --jq "map(select(.ref == \"refs/heads/$branch\")) | length")"
if [ "$exists" = 0 ]; then
    gh api "$api/git/refs" -f ref="refs/heads/$branch" -f sha="$base" > /dev/null
else
    gh api -X PATCH "$api/git/refs/heads/$branch" -f sha="$base" -F force=true > /dev/null
fi
blob="$(gh api "$api/contents/$path?ref=$branch" --jq .sha)"
gh api -X PUT "$api/contents/$path" -f branch="$branch" -f sha="$blob" -f content="$content" \
    -f message="$(printf '%s\n\n%s\n' "$title" "$body")" > /dev/null

if [ -z "$open" ]; then
    open="$(gh api "$api/pulls" -f head="$branch" -f base=main -f title="$title" -f body="$body" --jq .number)"
fi
echo "#$open proposes $path"
workflows="$("$(dirname "$0")/triggered.sh" pull_request refs/heads/main "$path")"
for workflow in $workflows; do
    gh workflow run "$workflow" --ref "$branch"
done
