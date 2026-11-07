# device

Everything that runs on the Raspberry Pi.

| Path          | What it is                                                         |
| ------------- | ------------------------------------------------------------------ |
| `image.json`  | Pinned Raspberry Pi OS image (URL, SHA-256)                        |
| `cloud-init/` | First-boot template rendered by DeKoi Setup                        |
| `host/`       | Files first boot installs: Quadlet units, firewall, policies       |
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

The canary image runs as two containers (ADR 0018): `dekoi-canary`
(`supervise.py canary`: OpenCanary and smbd) and `dekoi-announce`
(`supervise.py announce`: nmbd, avahi-daemon and wsdd2). To change the
decoy's name, model, firmware, shares or bait files, edit `canary/persona/`;
`persona.json` is filled into the templates when the image is built, and
the host name in `cloud-init/user-data.yaml` must match it (a test checks).
