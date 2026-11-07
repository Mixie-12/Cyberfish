# Release

Everything is built, signed and published by CI from a tag (ADR 0002).

1. Make sure `main` is green in all workflows.
2. Tag and push:

   ```
   git tag -a v0.1.0 -m "DeKoi 0.1.0"
   git push origin v0.1.0
   ```

3. `device images` builds `dekoi-canary`, `dekoi-agent` and `dekoi-ids` for
   arm64 and amd64, signs each digest, verifies the signature against
   `device/host/etc/dekoi/cosign.pub`, and only then moves `:stable`. Devices
   pick it up within a day through `podman auto-update` and roll back by
   themselves if the new image fails to start.
4. Check that the run is green and that `:stable` points at the new digest:

   ```
   docker buildx imagetools inspect ghcr.io/mixie-12/dekoi-agent:stable
   ```

The weekly scheduled run rebuilds the latest release on fresh Debian packages
and a fresh IDS ruleset, with the same signing steps.

One-time: after the first publish of each image, make its GHCR package public
(GitHub, Packages, the package, Package settings, Change visibility).
