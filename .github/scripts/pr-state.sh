#!/usr/bin/env bash
# Prints, as JSON, whether auto-merge.yml may merge pull request $1:
# {"number", "sha", "state", "reason"}, where state is "ready", "wait" (ask
# again later), "stop" (needs the owner) or "skip" (not from Dependabot or
# this repository's automation). Reads GitHub's records of the pull request
# only; nothing from it is checked out or run. Needs GH_TOKEN (actions,
# contents, pull-requests and statuses: read) and GH_REPO.
set -euo pipefail

pr="$(gh api "repos/$GH_REPO/pulls/$1")"
sha="$(jq -r .head.sha <<< "$pr")"
author="$(jq -r .user.login <<< "$pr")"
say() { jq -nc --argjson number "$1" --arg sha "$sha" --arg state "$2" --arg reason "$3" '$ARGS.named'; }

case "$author:$(jq -r .head.ref <<< "$pr")" in
    "dependabot[bot]:dependabot/"* | "github-actions[bot]:automation/"*) ;;
    *)
        say "$1" skip "not from Dependabot or the automation"
        exit 0
        ;;
esac
if ! jq -e --arg repo "$GH_REPO" '.head.repo.full_name == $repo and .base.ref == "main" and (.draft | not)' \
    <<< "$pr" > /dev/null; then
    say "$1" stop "its branch is not in this repository, it is not against main, or it is a draft"
    exit 0
fi

commits="$(gh api --paginate "repos/$GH_REPO/pulls/$1/commits" | jq -s add)"
if ! jq -e --arg author "$author" 'all(.[]; .author.login == $author and .commit.verification.verified)' \
    <<< "$commits" > /dev/null; then
    say "$1" stop "a commit on it is not by $author, or not signed by GitHub"
    exit 0
fi

refused="$(gh api --paginate "repos/$GH_REPO/pulls/$1/files" | jq -s add |
    jq -r --arg author "$author" -f "$(dirname "$0")/pr-files.jq" | paste -sd ';' -)"
if [ -n "$refused" ]; then
    say "$1" stop "$refused"
    exit 0
fi

# A pull request opened with GITHUB_TOKEN gets runs that wait for an
# approval; the runs the automation started for the same workflows stand in
# for them, and every workflow that GitHub started must have run. Runs that
# GitHub has not created yet look like no run at all, so a head younger than
# an hour waits.
runs="$(gh api --paginate "repos/$GH_REPO/actions/runs?head_sha=$sha&per_page=100" --jq '.workflow_runs' | jq -s add)"
committed="$(gh api "repos/$GH_REPO/commits/$sha" --jq .commit.committer.date)"
young="$(jq -n --arg committed "$committed" '(now - ($committed | fromdateiso8601)) < 3600')"
statuses="$(gh api "repos/$GH_REPO/commits/$sha/status" --jq '.total_count == 0 or .state == "success"')"
jq -c --argjson number "$1" --arg sha "$sha" --argjson young "$young" --argjson statuses "$statuses" '
    (map(select(.conclusion != "action_required")) | group_by(.path) | map(max_by(.created_at))) as $latest
    | ((map(.path) | unique) - ($latest | map(.path))) as $missing
    | [$latest[] | select(.status == "completed" and .conclusion != "success" and .conclusion != "skipped")
        | .name] as $failed
    | if $failed != [] then ["stop", "failed: \($failed | join(", "))"]
      elif length == 0 or $young then ["wait", "its head is new"]
      elif $missing != [] then ["stop", "did not run: \($missing | join(", "))"]
      elif any($latest[]; .status != "completed") or ($statuses | not) then ["wait", "checks are running"]
      else ["ready", "every check succeeded"] end
    | {number: $number, sha: $sha, state: .[0], reason: .[1]}' <<< "$runs"
