# 0005: Device runtime choices

Status: accepted, 2026-11-07

## Context

Decisions made while building and testing the device stack (Phase 2) that are
not obvious from the code.

## Decision

- Canary isolation: own user namespace (`UserNS=auto`, an unprivileged host
  uid), no capabilities, read-only root. Binding ports below 1024 without a
  capability needs `net.ipv4.ip_unprivileged_port_start=0`; acceptable on an
  appliance with no other users. `UserNS=auto` needs subordinate ID ranges for
  `containers`, which Debian does not create; first boot adds them.
- Agent privileges: host network and only `CAP_NET_RAW` (arp-scan, ARP
  watching); no user namespace, because the capability must apply to the host
  network namespace. Runs as uid 10002.
- OpenCanary is started with `twistd` directly; its wrapper needs sudo and a
  writable `/var/run`. SSH host keys live on a volume so each device keeps its
  own keys; a shared or changing key would identify DeKoi.
- Banners imitate current Ubuntu packages instead of OpenCanary's defaults,
  which are known honeypot fingerprints. OpenCanary only accepts MySQL banners
  starting with 3 to 6, so MySQL imitates 5.7.
- The HTTPS decoy stays off until each device can generate its own
  certificate; one certificate in every image would identify DeKoi.
- Network inventory scans the connected subnet capped at /22 (1024 addresses).
  `--localnet` on a /16 office network would send about 131,000 packets every
  15 minutes.
- Logs the agent reads must rotate by rename. Truncation in place can make it
  skip or split lines.
- Telnet honey credentials are removed: no login on any decoy ever succeeds.

## Consequences

- Host configuration written at first boot cannot be changed in the field;
  these choices are frozen per card until host configuration ships as a signed
  .deb (roadmap).
- OpenCanary exits completely if any decoy port is taken. Nothing else listens
  on the device, but the end-to-end test must mask the runner's sshd.
