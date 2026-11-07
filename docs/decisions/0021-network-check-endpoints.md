# 0021: Endpoints and rules of the network checks

Status: accepted, 2026-11-07. Fills in what ADR 0015 left open.

## Context

ADR 0015 asks for DNS over HTTPS at "one fixed provider", names compared
"by address family and ASN-independent rules", a speed test from "a fixed,
pinned HTTPS endpoint" at night, UPnP reads found by SSDP, and the public
address from a platform header. Each needed a concrete choice. Host
configuration (the firewall) is frozen once a card is written, so the
checks must work with the rules deployed devices already have.

## Decision

- DNS over HTTPS goes to Cloudflare by address, `https://1.1.1.1/dns-query`,
  in DNS wire format (RFC 8484). The certificate names the address, so the
  LAN resolver cannot redirect the check. Cloudflare also serves the speed
  test and fronts the Supabase API: one third party, not three.
- The names are `a.root-servers.net`, `one.one.one.one` and `dns.google`,
  A and AAAA. Their anycast addresses are the same from everywhere and have
  not changed in years, so overlap of the answer sets is a fair test
  without ASN data. Answers that lead nowhere (none, `0.0.0.0`, `::`) come
  from filters and do not count. Two names with disjoint answers is a
  mismatch, reported after two hourly checks in a row.
- The speed test downloads `https://speed.cloudflare.com/__down`, the
  endpoint of Cloudflare's own speed test, at most 25 MB and 30 s, between
  02:00 and 05:00 UTC: the device has no time zone, and this is night in
  Europe.
- The gateway's UPnP description URL comes from SSDP answers the discovery
  listener already receives; only an answer from the gateway's address that
  names an IGD counts. No new socket or firewall rule.
- The public address is `cf-connecting-ip`, else the first
  `X-Forwarded-For` entry, the header Supabase documents; the country is
  `cf-ipcountry` or nothing.

## Consequences

- Cloudflare sees an hourly lookup of public names from each DeKoi, and a
  nightly download from those whose owner turned the test on. No owner
  data is sent.
- A resolver that lies only about other names is not caught; the check
  finds resolvers that rewrite broadly.
- Owners outside Europe get the speed test at another time of day.
- Hourly samples wait in memory; an agent restart loses the hours not yet
  reported.
