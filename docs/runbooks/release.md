# Release

Everything is built, signed and published by CI from a tag (ADR 0002,
0022). Signing happens only in the `release` environment, which waits for
the owner's approval.

1. Make sure `main` is green in all workflows.
2. Tag and push:

   ```
   git tag -a v0.1.0 -m "DeKoi 0.1.0"
   git push origin v0.1.0
   ```

3. Approve: the run of `device images` and of `app release` each wait for
   the `release` environment (Actions, the run, Review deployments). Approve
   only a tag you pushed.
4. `device images` builds `dekoi-canary`, `dekoi-agent` and `dekoi-ids` for
   arm64 and amd64 without a build cache, refuses an image with a critical
   vulnerability that has a fix, signs each digest with the first cosign key
   and, once it exists, the Cloud KMS key, checks each signature against the
   public key the cards carry, and only then moves `:stable`. Devices pick it
   up within a day through `podman auto-update` and roll back by themselves
   if the new image fails to start.
5. Check that the run is green and that `:stable` points at the new digest:

   ```
   docker buildx imagetools inspect ghcr.io/mixie-12/dekoi-agent:stable
   ```

Every Monday the workflow starts itself on the latest release tag to
rebuild it on fresh Debian packages and IDS rules; that run also waits for
approval. Pushes to `main` publish unsigned `:edge` images to
`dekoi-*-edge`, which no device accepts.

To take back a signed image, see `rotate-keys.md`, "Take back a signed
image".

One-time: after the first publish of each image, make its GHCR package public
(GitHub, Packages, the package, Package settings, Change visibility). The
`-edge` packages need no change.

## Installer bundle

The `installer` workflow builds `dekoi-installer.zip` on every push to
`main` that changes `device/installer/` or `device/image.json`, and when
started by hand (Actions, installer, Run workflow). It pushes the zip to
`ghcr.io/mixie-12/dekoi-installer:<commit>` as one `application/zip` layer
and shows the pin for that build in the run's summary. Nothing uses it
until `device/installer.json` names it (ADR 0031):

1. Check that the run is green, then copy the JSON from its summary into
   `device/installer.json`:

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

3. Commit `device/installer.json` on its own; the apps take it at their
   next build and release.

One-time: after the first push, make the `dekoi-installer` package public
(GitHub, Packages, dekoi-installer, Package settings, Change visibility).
Until then step 2 fails and phones cannot fetch the bundle.
