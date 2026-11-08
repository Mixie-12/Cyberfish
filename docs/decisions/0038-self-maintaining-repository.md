# 0038: The repository updates, pins and releases itself

Status: accepted, 2026-11-08. Amends 0022 (release cadence, Actions
setting), 0027 and 0036 (who reports a failed run).

## Context

The owner wants the system to patch itself with nobody in the loop and
rarely opens the repository. Dependabot's pull requests waited for a merge;
`device/image.json`, the installer's Alpine pins and `device/installer.json`
were moved by hand; releases waited for a pushed tag, and devices get
container fixes only from a release; a failed run was visible only in
Actions. Constraints, from GitHub's documentation (2026-11-08) and this
repository: merges, tags and pull requests made with `GITHUB_TOKEN` start no
push, tag or pull request workflow (pull request runs wait for an
approval), `workflow_dispatch` does; `GITHUB_TOKEN` cannot write a workflow
file unless a branch already holds the same content; Actions may open pull
requests only with the repository setting on; the repository is private on
GitHub Pro (no environment reviewers); Dependabot alerts are off. No
personal token, GitHub App or new secret is wanted.

## Decision

- `auto-merge.yml`, every 6 hours on `main`'s code, merges (squash, bound to
  the checked head SHA) a pull request from `dependabot[bot]` or from this
  automation (`github-actions[bot]`, `automation/` branches) when every
  commit is that author's and signed by GitHub, every file is on the
  author's list in `pr-files.jq` (in workflows and Containerfiles, only
  `uses:` and `FROM` lines), every workflow run on the head succeeded, and
  `main` is green; one merge per run. It then starts on `main` what the push
  would have started. Otherwise it comments once per head and assigns the
  owner. No `pull_request_target`; nothing from a pull request is run.
- `triggered.sh` reads which workflows an event starts from the workflows'
  own filters, so nothing copies a path list. CI workflows gain
  `workflow_dispatch`; `app-release` acts only on a `v*` ref.
- `pins.yml`, daily: a Raspberry Pi OS Lite (64-bit) release from Raspberry
  Pi's OS list, 7 days old, same Debian release and first-boot format,
  downloaded and checked against the published SHA-256 and the list's
  sizes and extracted SHA-256; and Alpine package versions from the index.
  Check jobs have read-only tokens; `propose.sh` opens the pull request.
  `installer.yml` builds on pull requests and, after publishing on `main`,
  fetches the blob as a phone does and proposes its pin.
- `tag-release.yml`, daily: when `main` is green with commits since the last
  `v*` tag, and that tag is 7 days old or a Dependabot alert was fixed
  since, an annotated next patch tag; then it starts the tag's workflows.
- `failures.yml`: a failed run on `main`, a tag or a schedule opens or
  comments on "<workflow> failed", assigned to `github.repository_owner`;
  `repo.yml` checks that it names every workflow. `monitor.yml` checks that
  scheduled workflows still run; its own issue is gone.
- Dependabot keeps its cooldown and groups; majors come alone; base images
  stay on their release line. `apply.sh` lets Actions open pull requests and
  runs on a private repository, leaving out what needs a public one.

Stays manual: an Actions update after `main` changed the same workflow (the
merge is refused; the owner is asked); a new release line of Python, Alpine
or Debian, or of Raspberry Pi OS (needs a test on a Pi); failed Dependabot
updates; versions pinned inside workflows (gitleaks, OSV-Scanner, Grype,
ORAS, age, cosign, Supabase CLI, Deno, ruff, pytest, image digests in
`docker run`); applying `apply.sh`; approving releases once a reviewer can
be set.

## Consequences

- Security: a change lands only after the same workflows as a human
  change, plus the installer build on pull requests; supply-chain delay is
  Dependabot's and the image's 7 days (Alpine's replaced versions vanish, and
  apk checks the signed index). Signing is unchanged: release environment,
  `v*` refs, workload identity bound to tag, environment and workflow,
  signatures checked against the cards' keys. Write tokens exist only in
  scheduled jobs on `main`'s code. Letting Actions open pull requests adds
  nothing a job with `contents: write` could not already do; none approves.
- An Actions update reaches the release jobs, which can sign, after the
  cooldown and the checks but without a person reading it; most release
  steps are first exercised by the edge build after the merge. With a
  reviewer on `release` (public repository), every release and rebuild
  waits for the owner's click; removing it is the owner's call.
- Costs about 250 Actions minutes a month besides the CI it starts.
- Unverified until it runs on GitHub (STATUS.md lists it).
