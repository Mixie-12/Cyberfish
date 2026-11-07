# DeKoi by CyberFish

A plug-and-play network honeypot and intrusion detector. A Raspberry Pi poses
as a storage box on the local network; nothing legitimate talks to it, so any
contact is a signal. It also runs an IDS and watches the network for sweeps,
ARP spoofing, rogue DHCP servers and routers, and name poisoning. The owner
gets a plain-language alert on their phone.

| Directory   | What it is                                                     |
| ----------- | -------------------------------------------------------------- |
| `backend/`  | Supabase project: migrations, RLS, RPCs, Edge Function, tests  |
| `device/`   | Everything that runs on the Pi: decoys, IDS, agent, host config |
| `clients/`  | Gradle root: Android app, DeKoi Setup (SD-card tool), shared   |
| `docs/`     | Build brief, status, architecture, security, decisions         |

Each component README has its build, test and run commands.

Start with `docs/PROMPT.md` (what is being built) and `docs/STATUS.md` (where
it stands).
