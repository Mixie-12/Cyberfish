#!/bin/sh
# Runs Suricata on the interface that carries the default route, unless
# DEKOI_IDS_INTERFACE names another (the end-to-end test uses its bridge).
set -eu

interface="${DEKOI_IDS_INTERFACE:-$(awk '$2 == "00000000" { print $1; exit }' /proc/net/route)}"
if [ -z "$interface" ]; then
    echo "no default route yet" >&2
    exit 1
fi
exec suricata -c /etc/dekoi/suricata.yaml --af-packet="$interface"
