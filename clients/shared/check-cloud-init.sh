#!/bin/sh
# Validates the card files the shared renderer produces with cloud-init, in Debian
# trixie, the base of the pinned Raspberry Pi OS image. Runs in a container
# after `./gradlew :shared:test`; the command is in clients/README.md.
set -eu

rendered=/repo/clients/shared/build/rendered
host=/repo/device/host

apt-get update -qq
export DEBIAN_FRONTEND=noninteractive
# With python3-netplan, cloud-init validates network-config with netplan's own
# parser, which Raspberry Pi OS uses to apply it.
apt-get install -qq -y --no-install-recommends cloud-init python3-netplan >/dev/null
cloud-init --version

cloud-init schema --config-file "$rendered/user-data"
for config in "$rendered"/network-config-*; do
    cloud-init schema --schema-type network-config --config-file "$config"
done

# Parse with the YAML library cloud-init uses: every host file must arrive
# byte for byte, and the hostile network name must stay one key.
python3 - "$rendered" "$host" <<'EOF'
import pathlib
import sys

import yaml

rendered, host = (pathlib.Path(arg) for arg in sys.argv[1:])
user_data = yaml.safe_load((rendered / "user-data").read_text())
checked = 0
for entry in user_data["write_files"]:
    source = host / entry["path"].lstrip("/")
    if source.is_file():
        assert entry["content"] == source.read_text(), entry["path"]
        checked += 1
assert checked == sum(1 for path in host.rglob("*") if path.is_file()), checked

network = yaml.safe_load((rendered / "network-config-wifi").read_text())
access_points = network["network"]["wifis"]["wlan0"]["access-points"]
assert list(access_points) == [(rendered / "wifi-ssid").read_text()], access_points
print(f"{checked} host files and the network name round-trip exactly")
EOF
