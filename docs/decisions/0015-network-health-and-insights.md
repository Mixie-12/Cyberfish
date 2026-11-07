# 0015: Network health, exposure checks and new-device alerts

Status: accepted, 2026-11-07

## Context

The owner asked for more useful information in the box: network usage,
throughput, location. DeKoi sees only traffic to itself and broadcasts
(ADR 0003), so it cannot measure what other devices use. It can measure the
network from its own seat, check the router, and notice newcomers.

## Decision

- Internet health, from the DeKoi: every minute the agent measures the
  gateway (ICMP), the internet (TCP connect to the backend on 443) and the
  LAN resolver (one lookup); outages are consecutive failures. It reports
  hourly aggregates (median and worst latency, loss, outage minutes) through
  a new versioned device RPC; the backend keeps 90 days.
- DNS integrity: hourly, a fixed list of names is resolved through the LAN
  resolver and through DNS over HTTPS at one fixed provider, compared by
  address family and ASN-independent rules; a consistent mismatch is a
  `dns_hijack` event (critical).
- Exposure: hourly, if the gateway offers UPnP IGD, the agent reads its port
  mappings (read-only SOAP calls to the gateway on the local segment only)
  and reports them; a new mapping is a `port_forward` event (warning). The
  app lists what is open to the internet, by device.
- Public address: the backend records the address each heartbeat comes
  from, and the country only if the platform supplies it in a request
  header; no third-party geolocation. The device never reports it.
- Speed test: off by default; when the owner turns it on, the device runs
  one 25 MB download per night from a fixed, pinned HTTPS endpoint and
  reports Mbps. The setting reaches the device in the device RPC's reply,
  never as a command.
- New devices: after a 24-hour learning period from a DeKoi's first
  inventory, the first sighting of a MAC creates a `new_host` event
  (info), from a database trigger, not the agent.
- Weekly summary: a fixed-template push with counts only, once a week,
  which the owner can turn off in Settings.

## Consequences

- No per-device bandwidth; the app says why and points to the roadmap
  (mirror-port mode).
- New outbound traffic from the device: the DoH provider and, when enabled,
  the speed-test endpoint; both fixed, HTTPS, documented in
  docs/security.md.
- UPnP reads are requests to the router, the only active probe added; they
  never change a mapping.
