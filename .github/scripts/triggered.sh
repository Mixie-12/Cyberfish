#!/usr/bin/env bash
# Prints the workflow files that GitHub starts for EVENT (push or
# pull_request) on REF (refs/heads/<branch> or refs/tags/<tag>) when the
# PATHs changed; with no PATH, as if every path changed. Pushes, tags and pull
# requests made with GITHUB_TOKEN start no workflow, so the automation starts
# these by workflow_dispatch instead (ADR 0038). The filters are read from the
# workflow files, the one place they are defined; triggered.jq fails on filter
# syntax it does not model rather than guess. Run from the repository root.
set -euo pipefail

event="$1" ref="$2"
shift 2
case "$event:$ref" in
    push:refs/tags/*) kind=tags name="${ref#refs/tags/}" ;;
    push:refs/heads/* | pull_request:refs/heads/*) kind=branches name="${ref#refs/heads/}" ;;
    *)
        echo "triggered.sh: no $event on $ref" >&2
        exit 2
        ;;
esac
paths="$(jq -nc '$ARGS.positional' --args "$@")"

for workflow in .github/workflows/*.yml; do
    started="$(yq -o=json '.on' "$workflow" | jq --arg event "$event" --arg kind "$kind" \
        --arg name "$name" --argjson paths "$paths" -f "$(dirname "$0")/triggered.jq")"
    if [ "$started" = true ]; then basename "$workflow"; fi
done
