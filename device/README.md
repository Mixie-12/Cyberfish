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
| `installer/`  | The installer bundle the phone copies onto a card (ADR 0031, 0033) |
| `installer.json` | The published installer bundle the apps use (format under "Installer") |

Run from this directory:

```
uvx ruff@0.17.4 check . && uvx ruff@0.17.4 format --check .
uvx --python 3.13 --from pytest==8.3.5 pytest -q     # Linux; on Windows run it in the agent's base image
docker build -f canary/Containerfile -t dekoi-canary:local .
docker build -f agent/Containerfile -t dekoi-agent:local .
docker build -f ids/Containerfile -t dekoi-ids:local .
docker build -f installer/Containerfile -o out .         # out/dekoi-installer.zip; needs QEMU for arm64
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
user names come from `bait_users` in `persona.json`, which the agent image
also carries: a sign-in with one of them gets `"bait": true` in its detail.

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

## Installer

ADR 0031 is the contract, ADR 0033 what the bundle carries and assumes. The
phone copies the entries of `dekoi-installer.zip` to the root of a FAT32
card, next to `dekoi/user-data`, `dekoi/network-config` and
`dekoi/meta-data`. A Raspberry Pi 4 or 5 starts the bundle's kernel with
the initramfs, whose init is `installer/install.py`; it downloads the
image pinned in `image.json`, proves it, writes it over the card, adds the
three cloud-init files and the `ds=nocloud;i=<instance-id>` suffix to the
new `cmdline.txt`, and restarts into Raspberry Pi OS.

| Path                      | What it is                                                   |
| ------------------------- | ------------------------------------------------------------ |
| `installer/install.py`    | The installer, `/init` in the initramfs                      |
| `installer/bundle.py`     | Build steps the Containerfile runs                           |
| `installer/boot/`         | DeKoi's `config.txt` and `cmdline.txt` for the card          |
| `installer/Containerfile` | Builds `dekoi-installer.zip`                                 |

The zip holds, all at the card's root: `config.txt`, `cmdline.txt`,
`dekoi-installer.cpio.gz` (Alpine's busybox, Python and CA certificates,
the installer and `image.json`), and from the image's boot partition,
unchanged, `kernel8.img`, `kernel_2712.img`, `start4.elf`, `fixup4.dat`,
its `LICENCE.broadcom` and the device trees in `FIRMWARE` in `bundle.py`.
The build is meant to be reproducible: two local builds gave the same zip.

On the Pi the activity LED blinks evenly (0.5 s) while it works and fast
(0.1 s) once it stopped; the reason is on the screen (HDMI) and on the Pi
5's debug UART. Before writing starts the card is untouched: it retries the
network, and after any other stop the next start tries again.

`installer.json` pins the published bundle for the apps:

```json
{"repository": "mixie-12/dekoi-installer", "digest": "sha256:<64 hex digits>", "size": <bytes>}
```

`digest` is the SHA-256 of the zip, the single `application/zip` layer of
the artifact at `ghcr.io/<repository>`; the phone fetches that blob by
digest and checks size and hash. The `installer` workflow builds the
bundle for every pull request that changes `installer/` or `image.json`,
pushes it on every such change on `main`, and proposes the new line for
this file in a pull request that `auto-merge` merges after the checks
(`docs/runbooks/release.md`, ADR 0036). The unit tests run with the other
device tests (Linux only: the installer uses `fcntl`).
