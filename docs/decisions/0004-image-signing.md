# 0004: Signing device images

Status: accepted, 2026-11-07. Amended by 0022 (keys in CI, signing, rotation).

## Context

Devices update themselves from GHCR without anyone present. Whoever can push
an image the devices accept controls every customer's honeypot. Podman can
enforce signatures natively (`policy.json`, `sigstoreSigned`), so no extra
software is needed on the device.

## Decision

- One cosign key pair. The public key is on every card
  (`/etc/dekoi/cosign.pub`); Podman rejects every image that is not from
  `ghcr.io/mixie-12` and signed with it.
- Key-based signing without a transparency log and in the classic attachment
  format (`--use-signing-config=false --new-bundle-format=false
  --tlog-upload=false`). cosign 3 defaults to a signing config and a new bundle
  format that Podman 5.4 does not read; tested locally and in CI.
- The release workflow signs the image digest, verifies the signature against
  the committed public key, and only then moves the channel tag. A device can
  never see an unsigned `:stable`.
- The private key and its password are GitHub Actions secrets, with a backup in
  the owner's 1Password (vault "Encryption"). Generated 2026-11-07; no copy
  remains on disk.

## Consequences

- Losing the private key means devices in the field can no longer be updated;
  they must be re-flashed with a card carrying a new public key. The 1Password
  backup exists for that reason.
- Rotating the key also requires re-flashing; host configuration is frozen at
  flash time (roadmap: host configuration as a signed .deb).
- No Rekor transparency log: signing activity is not publicly auditable. The
  key never leaves CI, and verification does not depend on an external
  service.
