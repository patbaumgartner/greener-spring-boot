#!/usr/bin/env bash
# Detect the available power source for energy measurement.
#
# Priority:
#   rapl          — energy_uj readable   → hardware measurement
#   vm-file       — VM_POWER_FILE set     → Scaphandre VM measurement
#   ci-estimated  — /proc/stat available  → CPU-time × TDP estimation
#   none          — nothing available     → skip measurement
#
# Output: writes POWER_SOURCE=<value> to the file specified by $1
# (typically $GITHUB_OUTPUT for GitHub Actions).
#
# Usage:
#   bash detect-power-source.sh "$GITHUB_OUTPUT"

set -euo pipefail

OUTPUT_FILE="${1:?Usage: detect-power-source.sh <output-file>}"

if [ -r /sys/class/powercap/intel-rapl/intel-rapl:0/energy_uj ]; then
    echo "POWER_SOURCE=rapl" >> "$OUTPUT_FILE"
    echo "RAPL readable — hardware energy measurement will run."
elif [ -n "${VM_POWER_FILE:-}" ] && [ -f "${VM_POWER_FILE}" ]; then
    echo "POWER_SOURCE=vm-file" >> "$OUTPUT_FILE"
    echo "Scaphandre VM power file found at ${VM_POWER_FILE}."
elif [ -f /proc/stat ]; then
    echo "POWER_SOURCE=ci-estimated" >> "$OUTPUT_FILE"
    echo "No hardware RAPL access — using CPU-time x TDP software estimation."
    echo "Results are reproducible on the same runner type."
    echo "See: examples/vm-setup/README.md"
else
    echo "POWER_SOURCE=none" >> "$OUTPUT_FILE"
    echo "No power source available — measurement skipped."
fi
