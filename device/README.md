# device

Everything that runs on the Raspberry Pi.

| Path          | What it is                                                         |
| ------------- | ------------------------------------------------------------------ |
| `image.json`  | Pinned Raspberry Pi OS image (URL, SHA-256)                        |
| `cloud-init/` | First-boot template rendered by DeKoi Setup                        |
| `host/`       | Files first boot installs: Quadlet units, firewall, policies, the Wi-Fi scan |
| `image.json`  | Pinned Raspberry Pi OS image (URL, sizes, SHA-256)                 |
| `cloud-init/` | First-boot template rendered by DeKoi Setup and the app            |
| `host/`       | Files first boot installs: Quadlet units, firewall, policies       |
| `canary/`     | `dekoi-canary` image: OpenCanary decoys                            |
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
