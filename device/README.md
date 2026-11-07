# device

Everything that runs on the Raspberry Pi.

| Path          | What it is                                                         |
| ------------- | ------------------------------------------------------------------ |
| `image.json`  | Pinned Raspberry Pi OS image (URL, sizes, SHA-256)                 |
| `cloud-init/` | First-boot template rendered by DeKoi Setup and the app            |
| `host/`       | Files first boot installs: Quadlet units, firewall, policies, the Wi-Fi scan |
| `canary/`     | `dekoi-canary` image: OpenCanary, the Samba file share and their announcements (mDNS, WS-Discovery, NetBIOS); `persona/` is the decoy's identity |
| `ids/`        | `dekoi-ids` image: Suricata with the ET Open ruleset               |
| `agent/`      | `dekoi-agent` image: the agent, standard-library Python            |
| `e2e/`        | End-to-end test (CI runs it on a throwaway machine)                |

Run from this directory:

```
uvx ruff@0.17.4 check . && uvx ruff@0.17.4 format --check .
uvx --python 3.13 --from pytest==8.3.5 pytest -q     # Linux; on Windows run it in the agent's base image
docker build -f canary/Containerfile -t dekoi-canary:local .
docker build -f agent/Containerfile -t dekoi-agent:local .
docker build -f ids/Containerfile -t dekoi-ids:local .
```

The end-to-end test needs root, systemd and Podman; see the `e2e` job in
`.github/workflows/device.yml` for the exact steps.

The host's Wi-Fi scan script and units are checked in Debian trixie by the
`checks` job (shellcheck, `systemd-analyze verify`); on Windows run that
step's commands in the same Debian image with Docker.

The canary image runs as two containers (ADR 0018): `dekoi-canary`
(`supervise.py canary`: OpenCanary and smbd) and `dekoi-announce`
(`supervise.py announce`: nmbd, avahi-daemon and wsdd2). To change the
decoy's name, model, firmware, shares or bait files, edit `canary/persona/`;
`persona.json` is filled into the templates when the image is built, and
the host name in `cloud-init/user-data.yaml` must match it (a test checks).
The bait files in `persona/public` are text; one named like `x.pdf.txt`
becomes `x.pdf` (also `.xlsx`, `.docx`, `.png`; `canary/render.py`). Their
user names come from `bait_users` in `persona.json`.

## Wi-Fi watch

ADR 0019, and "Wi-Fi watch" in `docs/architecture.md`. The host and the
agent share two directories in `/run`, created by
`host/etc/tmpfiles.d/dekoi-wifi.conf`:

| Path                               | Written by                     | Read by |
| ---------------------------------- | ------------------------------ | ------- |
| `/run/dekoi/wifi-country/country`  | agent (uid 10002), while the watch is on | `host/usr/local/libexec/dekoi-wifi-scan` |
| `/run/dekoi/wifi/scan`             | the scan, every five minutes (`dekoi-wifi-scan.timer`) | agent, read-only mount |

The agent parses the scan in `agent/dekoi_agent/wifi.py` and keeps what it
learned in `wifi.json` beside its state. A card written before the watch
has neither directory; its agent reports `no_radio`.
