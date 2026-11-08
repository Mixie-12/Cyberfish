#!/usr/bin/env bash
# Prints "true" when main is green, else "false": for every workflow that a
# push to main starts, the latest run on main that was not cancelled finished
# and succeeded. With path filters, that run may be on an older commit; it is
# still the last word on that workflow's part of main. A workflow that never
# ran on main counts as green. Each workflow's state goes to stderr; an error
# fails. Needs GH_TOKEN (actions: read) and GH_REPO; run from the repository
# root.
set -euo pipefail

workflows="$("$(dirname "$0")/triggered.sh" push refs/heads/main)"
green=true
for workflow in $workflows; do
    state="$(gh run list --workflow "$workflow" --branch main --limit 20 --json status,conclusion \
        --jq 'map(select(.conclusion != "cancelled")) | first // {status: "never ran"}
          | [.status, .conclusion // empty] | join(" ")')"
    echo "main, $workflow: $state" >&2
    case "$state" in
        "completed success" | "never ran") ;;
        *) green=false ;;
    esac
done
echo "$green"
