# 0018: A decoy that is easy to find

Status: accepted, 2026-11-07

## Context

On the owner's network the DeKoi was invisible: no file share, nothing in
Windows Explorer's Network view, Finder or the Files app, and no name that
invites a look. Decoys that only answer port scans miss the most common
intruder path (browsing the network for shares) and cannot be tried out by
the owner. The owner asked for a decoy that is the first thing anyone sees.

## Decision

- One persona, a home storage server with an inviting, plausible, brand-free
  name (hostname, NetBIOS and mDNS name, web title), defined once in the
  canary image's persona file so the app can show it and a later version can
  offer more personas.
- File share: Samba in the canary container. Share names are listed to
  anyone (so browsing shows them, as on a real NAS); a guest-readable share
  holds harmless bait files; every other share asks for a password. Samba's
  JSON authentication audit gives the user name (never the password);
  `full_audit` gives opened files. Sign-in attempts are `login_attempt`
  (service `smb`); opening a bait file is a new `file_access` event,
  critical.
- Web interface: a brand-free storage-manager login page (OpenCanary http
  with our own skin) on port 80, and on 5000 like common NAS systems.
- Announcements, so it appears everywhere: mDNS through avahi-daemon
  without D-Bus (`_smb._tcp`, `_http._tcp`, `_device-info._tcp` with a server
  model, so Finder shows a server icon), WS-Discovery through `wsdd` for
  Windows Explorer, NetBIOS names through `nmbd`. All in the canary
  container, never in the agent or on the host.
- Firewall: open exactly what these need to the canary (TCP 445 and 5000,
  UDP 137, 138, 5353, 3702, TCP 5357), nothing else.
- Noise: discovery traffic (mDNS, WS-Discovery, NetBIOS lookups) never
  alerts. Alerts start where a person or tool acts: listing a share's
  contents, a sign-in attempt, opening a file, the web login.

## Consequences

- More parsing code faces the LAN (Samba, avahi, wsdd), all inside the
  canary's isolation (own user namespace, no capabilities, read-only root,
  no secrets, ADR 0005); images rebuild weekly for security updates.
- Legitimate devices browsing the network see the decoy; only acting on it
  alerts. The owner can test the decoy from their own computer.
- Existing cards keep their old host name and firewall until rewritten; the
  canary image change reaches them through updates, but the new firewall
  ports do not.
