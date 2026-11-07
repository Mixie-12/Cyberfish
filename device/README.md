# device

Everything that runs on the Raspberry Pi.

| Path          | What it is                                                         |
| ------------- | ------------------------------------------------------------------ |
| `image.json`  | Pinned Raspberry Pi OS image (URL, SHA-256)                        |
| `cloud-init/` | First-boot template rendered by DeKoi Setup                        |
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
