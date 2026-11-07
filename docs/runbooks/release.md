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
