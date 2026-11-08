# 0037: Updating host configuration in the field

Status: proposed, 2026-11-08. Needs the owner's decision; nothing is built.

## Context

First boot writes the host configuration and nothing changes it afterwards
(ADR 0004, 0005); the images update daily. Found while auditing for
ADR 0036, these can only change by writing a new card today: the firewall's
decoy ports (the finance server of ADR 0035 opens eight new ones, closed on
every older card), the Quadlet units (image names, capabilities, volumes,
the watchdog), the trusted signing keys (the KMS switch of ADR 0022), the
apt and unattended-upgrades settings, journald and logrotate limits, the
Wi-Fi scan script, and the move to the next Debian release before LTS ends
in June 2030. Customers will not rewrite cards. A trust anchor for any
update path can only reach a device through its card, so the choice must be
made before cards go to customers; every card written before it stays
frozen.

## Options

1. **A signed Debian package.** CI builds `dekoi-host` (the files in
   `device/host`, units enabled by its scripts) into an apt repository on
   static HTTPS hosting (GitHub Pages or a release asset). The card carries
   a `Signed-By` keyring, one sources entry and the repository's origin in
   `52dekoi`; unattended-upgrades, which already runs daily, installs it
   with the conffile rule and the 03:30 reboot of ADR 0036. apt verifies
   the signature, so the hosting need not be trusted. Needs an OpenPGP signing key: Cloud KMS makes raw signatures, not
   OpenPGP, so it would be a new key in the `release` environment with an
   offline backup, like the first cosign key.
2. **Host files in the signed agent image.** A root oneshot on the host
   mounts the image Podman already verified and copies an allowlist of
   files. No new key or hosting, but the image signing key, today limited
   to containers with two capabilities, becomes root on every host, and a
   bad file has no package manager to undo it.
3. **Stay frozen.** Host changes reach customers only on new cards.

## Proposal

Option 1. It keeps root-level changes behind a key of their own, reuses
Debian's tooling for ordering, conffiles and removal, and adds no daemon.
Guards: the package ships only DeKoi's files; its postinst checks a new
ruleset with `nft -c` before loading it and keeps the old one otherwise;
releases are staged (a test device first) because a broken network rule
cannot be fixed remotely. The owner decides on the key's custody and the
hosting; then new cards carry the keyring, and existing cards are
rewritten once.

## Consequences

- Until then, each host change in `device/host` reaches only cards written
  after it, as `docs/STATUS.md`, "Known limitations" says.
- A compromised package key would be root on every device; its custody must
  match the image signing key's at least.
