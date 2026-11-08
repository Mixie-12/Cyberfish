# 0035: The decoy is a small company's finance server

Status: accepted, 2026-11-08. Replaces the persona of ADR 0018 and amends
the bait rules of ADR 0024.

## Context

The family NAS of ADR 0018 invited a look but promised little: photos, a
tax return, home passwords. Intruders and their tools rank targets by what
they hold: payment data, ERP systems, domain and database credentials,
backups, control systems, best on old software. The owner asked for a
decoy that a scanner or a person on a home or small-office network would
go for first. Devices in the field keep the host name `family-nas` and the
firewall their card gave them; only their images update.

## Decision

- One persona: `sap-pay-prod`, the central server of Kaldskjær Sjømat AS,
  a made-up seafood company with Finance, HR, IT and Operations and a cold
  store: Windows Server 2008 R2 with SAP ECC 6.0, SQL Server, a card
  payment portal, file shares, backups and the way in to the cold store's
  PLC. `device/canary/persona/persona.json` holds its name, company,
  workgroup, domain, description, SAP system and bait user names;
  everything else, OpenCanary's configuration included, is rendered from it.
- Banners of that era: IIS 7.5, OpenSSH for Windows 7.7, Microsoft FTP and
  Telnet, MySQL 5.1, SQL Server 2008 R2, SAP ICM and J2EE. Product names are
  text only; pages and bait are our own text and CSS.
- New decoys from OpenCanary in the canary container, so its isolation is
  unchanged: tcpbanner on 3200, 3299, 3600, 8000 and 50000 (service `sap`),
  502 (`modbus`) and 102 (`s7`), and MSSQL on 1433. They report the existing
  kinds, `connection` and `login_attempt`. Binary protocols say nothing
  first, as the real servers wait for the client; the two web ports answer
  401 with SAP's server header. New cards open the eight ports and no longer
  redirect port 5000, a NAS habit. The announcements stay as in ADR 0018.
- Bait: one open share, Felles, lived in: files named and dated over the
  last months. ADR 0024 holds, and a test now checks the numbers: cards fail
  the Luhn check (they look real at first glance; none can be a card),
  account and organisation numbers fail their mod 11 check digit, personal
  numbers stay synthetic. Addresses are private or documentation ranges, no
  real domain or company, no vendor's default password (SAP*'s opens real
  systems). Seven honeytokens; one typed with a domain
  (`KALDSKJAER\ks-drift`, `ks-drift@...`) is flagged too.
- Devices in the field: no decoy takes its name from the Pi's host name.
  Samba, avahi and wsdd2 read the persona's name from the image, so an
  updated device shows `SAP-PAY-PROD` in Explorer, Finder, NetBIOS, the share
  list and the web page, as the app (DECOY_NAME, from the persona) says.
  Rejected: keeping `family-nas` for old devices, which needs the persona
  rendered at start from the host name and a second name in the app.

## Consequences

- Cards from before keep `family-nas` in the router's device list, local
  DNS and the DNS name in Samba's sign-in challenge, which Samba takes from
  the kernel (tested; `dns hostname` does not change it), and their old
  firewall: the new decoys listen but stay closed, and probes there count as
  a port scan. Writing the card again fixes both.
- A careful fingerprint shows Linux, not Windows: the TCP/IP stack, SMB 3.1.1
  and OpenCanary's own Telnet prompt. Scanners and most intruders go by
  banners and names.
- tcpbanner has no idle time-out: like several OpenCanary decoys, a LAN host
  can hold connections open; that ties up the decoys only.
- The bait's dates are fixed and age with the image; refresh them now and
  then. 13 decoy services; the heartbeat takes at most 16.
- Departs from `docs/PROMPT.md` ("a generic network storage box") at the
  owner's request.
