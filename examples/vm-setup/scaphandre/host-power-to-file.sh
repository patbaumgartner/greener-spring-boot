#!/usr/bin/env sh
# host-power-to-file.sh
#
# Scaphandre "json" exporter helper for Joular Core VM mode (Option B).
#
# Scaphandre writes a JSON file with per-process power data.  This script reads
# that file once per second, extracts the total Watts for the monitored VM
# process(es), and writes a single plain-text Watt value that Joular Core can
# consume via --vm-power-file.
#
# Run this on the KVM HOST alongside Scaphandre, before starting the energy
# measurement in the guest.
#
# Usage:
#   bash host-power-to-file.sh <scaphandre-json-output> <power-file-for-guest>
#
# Example:
#   sudo scaphandre json \
#     --process-regex "qemu.*myvm" \
#     --step 1 \
#     --output /srv/greener-power-share/scaphandre.json &
#
#   bash host-power-to-file.sh \
#     /srv/greener-power-share/scaphandre.json \
#     /srv/greener-power-share/vm-power.txt &
#
# Requirements on the host:
#   - Scaphandre >= 0.5.0   https://github.com/hubblo-org/scaphandre
#   - python3 (any 3.x)
#
# The output file is updated every second and must be readable by the guest
# (e.g. via a virtio-fs mount, NFS export, or shared volume).

set -eu

SCAPHANDRE_JSON="${1:?Usage: $0 <scaphandre-json> <power-output-file>}"
POWER_FILE="${2:?Usage: $0 <scaphandre-json> <power-output-file>}"

echo "Starting host power exporter"
echo "  Input:  ${SCAPHANDRE_JSON}"
echo "  Output: ${POWER_FILE}"

# Ensure output directory exists
mkdir -p "$(dirname "${POWER_FILE}")"

# Write an initial value so Joular Core can start reading immediately
echo "0.0" > "${POWER_FILE}"

while true; do
    if [ -f "${SCAPHANDRE_JSON}" ]; then
        # Extract the sum of all consumer power values from the Scaphandre JSON.
        # Scaphandre JSON schema (simplified):
        #   { "consumers": [ { "exe": "qemu-system-x86_64", "consumption": 12345678 }, ... ] }
        # "consumption" is in microwatts; divide by 1_000_000 to get Watts.
        WATTS=$(python3 - "${SCAPHANDRE_JSON}" << 'PYEOF'
import json, sys, os
path = sys.argv[1]
try:
    with open(path) as f:
        data = json.load(f)
    consumers = data.get("consumers", [])
    total_uw = sum(c.get("consumption", 0) for c in consumers)
    print(round(total_uw / 1_000_000, 3))
except Exception as e:
    print(0.0)
PYEOF
)
        echo "${WATTS}" > "${POWER_FILE}"
    fi
    sleep 1
done
