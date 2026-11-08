<p align="center">
  <img src="Images/cyberfish-app-icon.svg" width="96" alt="CyberFish logo">
</p>

# DeKoi by CyberFish

A plug-and-play network honeypot and intrusion detector. A Raspberry Pi poses
as a small company's finance server on the local network; nothing legitimate
talks to it, so any contact is a signal. It also runs an IDS and watches the
network for sweeps, ARP spoofing, rogue DHCP servers and routers, and name
poisoning. The owner gets a plain-language alert on their phone.

<img src="Images/dekoi-what-it-does.svg" alt="Bob uses the home network and never touches DeKoi. Eve scans what looks like a company's finance server and tries to sign in. DeKoi reports it over HTTPS, and Alice gets an alert on her phone within seconds.">

## How it works

<img src="Images/dekoi-how-it-works.svg" alt="The Pi's firewall lets Eve reach only the decoys. The agent reads the decoy and IDS logs one way, drops typed passwords and sends events to Supabase over HTTPS, outbound only. The device API checks the device secret and every field, row-level security keeps each owner's data separate, and a fixed alert reaches Alice's phone through Firebase Cloud Messaging. The Pi runs only signed images.">

The contracts behind this picture are in `docs/architecture.md`; the evidence
for each security claim is in `docs/security.md`.

| Directory   | What it is                                                     |
| ----------- | -------------------------------------------------------------- |
| `backend/`  | Supabase project: migrations, RLS, RPCs, Edge Function, tests  |
| `device/`   | Everything that runs on the Pi: decoys, IDS, agent, host config |
| `clients/`  | Gradle root: Android app, DeKoi Setup (SD-card tool), shared   |
| `docs/`     | Build brief, status, architecture, security, decisions         |

Each component README has its build, test and run commands.

Start with `docs/PROMPT.md` (what is being built) and `docs/STATUS.md` (where
it stands).

## The 3D DeKoi

<img src="Images/cyberfish-landing-page.jpg" alt="The CyberFish landing page: a 3D robotic koi with glowing blue circuit lines swims above a dark sea, next to the text 'DeKoi. A plug-in network decoy for homes and small offices.'">

The CyberFish landing page is a three.js scene with DeKoi as its mascot. It
was built during the hackathon as the visual for the pitch and as a starting
point for an advertising campaign. It is a separate asset, not part of the
product, so it lives in its own repository:
[nevermindmae/cyberfish-landing-page](https://github.com/nevermindmae/cyberfish-landing-page).
It follows the same rules as this repository.
