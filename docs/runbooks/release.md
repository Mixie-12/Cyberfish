# Release

Everything is built, signed and published by CI from a tag (ADR 0002,
0022), and CI makes the tags (ADR 0038). Signing happens only in the
`release` environment, deployable from `v*` tags only; once the repository
is public and `infra/github/apply.sh` has run, it also waits for the
owner's approval.

## Automatic releases

`tag-release.yml` runs every morning. When `main` is green (the latest run
on `main` of every workflow a push to `main` starts has succeeded), has
commits after the highest `v*` tag, and that tag is at least 7 days old or
a Dependabot alert was fixed after it, it tags the next patch version
(`v0.3.1` → `v0.3.2`, annotated "DeKoi 0.3.2") and starts on the tag what a
pushed tag starts: `app release`, `clients` (DeKoi Setup MSI) and `device
images`. A tag made with the workflow's token starts nothing by itself,
hence `workflow_dispatch` on the tag; the workflows read the version from
the tag exactly as for a pushed tag. A failed run opens the issue
"<workflow> failed" for the owner (`failures.yml`).

To release now, start it by hand (Actions, tag release, Run workflow); it
still releases only under the conditions above. For anything else (a minor
or major version), tag by hand as below.

## By hand

1. Make sure `main` is green in all workflows.
2. Tag and push:

   ```
   git tag -a v0.4.0 -m "DeKoi 0.4.0"
   git push origin v0.4.0
   ```

3. Approve, if the `release` environment has a reviewer: the runs of
   `device images` and `app release` each wait for it (Actions, the run,
   Review deployments). Approve only a tag you or `tag-release.yml` made.

## What a release does

`device images` builds `dekoi-canary`, `dekoi-agent` and `dekoi-ids` for
arm64 and amd64 without a build cache, refuses an image with a critical
vulnerability that has a fix, signs each digest with the first cosign key
and, once it exists, the Cloud KMS key, checks each signature against the
public key the cards carry, and only then moves `:stable`. Devices pick it
up within a day through `podman auto-update` and roll back by themselves
if the new image fails to start. To check that `:stable` points at the new
digest:

```
docker buildx imagetools inspect ghcr.io/mixie-12/dekoi-agent:stable
```

Every Monday the workflow starts itself on the latest release tag to
rebuild it on fresh Debian packages and IDS rules. Pushes to `main`, and
the runs `auto-merge.yml` starts on `main`, publish unsigned `:edge` images
to `dekoi-*-edge`, which no device accepts.

To take back a signed image, see `rotate-keys.md`, "Take back a signed
image".

One-time: after the first publish of each image, make its GHCR package public
(GitHub, Packages, the package, Package settings, Change visibility). The
`-edge` packages need no change.

## Dependencies and pins

`auto-merge.yml` merges, every 6 hours, Dependabot's pull requests and the
ones this automation opens, once every check on them succeeded and `main`
is green (ADR 0038). `pins.yml` proposes, every day, a new Raspberry Pi OS
Lite (64-bit) for `device/image.json` and new Alpine package versions for
`device/installer/Containerfile`. A pull request that needs the owner gets
a comment saying why and is assigned to the owner: fix it on its branch,
or close it; a refused Actions update usually only needs Dependabot to
rebase it (comment `@dependabot rebase`). A Raspberry Pi OS on a new
Debian release opens an issue instead: test it on a Pi with a card from
DeKoi Setup, then edit `device/image.json` by hand.

## Installer bundle

The `installer` workflow builds `dekoi-installer.zip` for pull requests
that change `device/installer/` or `device/image.json`, and on `main`
pushes it to `ghcr.io/mixie-12/dekoi-installer:<commit>` as one
`application/zip` layer. Its pin job then fetches the blob without signing
in, as a phone does, checks SHA-256 and size, and proposes the pin for
`device/installer.json` in a pull request that `auto-merge.yml` merges; the
apps take it at their next build and release (ADR 0031, 0038). By hand,
for example when the pin job failed:

1. Check that the `publish` job is green, then copy the JSON from its
   summary into `device/installer.json`:

   ```json
   {"repository": "mixie-12/dekoi-installer", "digest": "sha256:...", "size": 12345}
   ```

2. Fetch the blob without signing in, as the phone does, and compare its
   SHA-256 and size with the pin:

   ```
   token=$(curl -fsS "https://ghcr.io/token?scope=repository:mixie-12/dekoi-installer:pull" | jq -r .token)
   curl -fsSL -H "Authorization: Bearer $token" -o dekoi-installer.zip \
     "https://ghcr.io/v2/mixie-12/dekoi-installer/blobs/sha256:<digest>"
   sha256sum dekoi-installer.zip; stat -c %s dekoi-installer.zip
   ```

3. Commit `device/installer.json` on its own.

The `dekoi-installer` package is public (anonymous pull verified
2026-11-08); without that, step 2 and the pin job fail and phones cannot
fetch the bundle.
