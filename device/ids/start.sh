#!/bin/sh
# Runs Suricata on the interface that carries the default route, unless
# DEKOI_IDS_INTERFACE names another (the end-to-end test uses its bridge).
set -eu

interface="${DEKOI_IDS_INTERFACE:-$(awk '$2 == "00000000" { print $1; exit }' /proc/net/route)}"
if [ -z "$interface" ]; then
    echo "no default route yet" >&2
    exit 1
fi
read -r version updated_at < /usr/share/dekoi/image
read -r ruleset_date < /var/lib/suricata/rules/ruleset-date
# Suricata adds the sensor name to every record it sends, so the agent learns
# which image and ruleset are running from the minutely statistics, also
# after the agent itself restarts (ADR 0013, dekoi_agent/monitors.py).
exec suricata -c /etc/dekoi/suricata.yaml --af-packet="$interface" \
    --set "sensor-name=dekoi-ids $version $updated_at $ruleset_date"
