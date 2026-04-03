#!/usr/bin/env bash
# ci-cpu-energy-estimator.sh
#
# Estimates total system CPU power on Linux by reading /proc/stat CPU-time
# deltas once per second and multiplying the active fraction by a configurable
# TDP value.  The result is written to a plain-text file so Joular Core can
# consume it via --vm --vm-power-file.
#
# This enables energy measurement in any Linux environment where /proc/stat is
# readable: GitHub Actions, GitLab CI, Jenkins agents, WSL2, and generic VMs
# — with no root privileges and no hardware RAPL access required.
#
# How it works
# ─────────────
# Each second the script reads the CPU idle and total jiffies from /proc/stat,
# computes CPU utilisation = 1 − (Δidle / Δtotal), and writes
#   estimated_power_W = cpu_utilisation × TDP_W
# to the output file.
#
# Joular Core (--vm mode) reads that file as the total VM power and then
# attributes a fraction of it to the monitored PID based on that process's own
# CPU time share.
#
# TDP guidance
# ─────────────
# GitHub Actions ubuntu-latest (Intel Xeon Platinum 8370C, 32 cores, 270 W)
#   → 2–4 vCPUs per runner → use 100 W (conservative)
# GitLab / Jenkins self-hosted: use the actual CPU TDP of your runner host.
# WSL2 on a laptop (Intel Core i7, 28–45 W TDP): use the laptop TDP.
#
# Usage
# ─────
#   bash ci-cpu-energy-estimator.sh <output-file> [tdp-watts]
#
# Arguments
#   output-file   File to write the estimated Watts to (updated every second).
#                 The directory is created if it does not exist.
#   tdp-watts     Thermal design power in Watts.  Default: 100.
#
# Example
#   bash examples/vm-setup/ci-cpu-energy-estimator.sh /tmp/ci-power.txt 100 &
#   ESTIMATOR_PID=$!
#   # … run energy measurement …
#   kill $ESTIMATOR_PID

set -eu

OUTPUT_FILE="${1:?Usage: $0 <output-file> [tdp-watts]}"
TDP="${2:-100}"

mkdir -p "$(dirname "${OUTPUT_FILE}")"
printf "0.000\n" > "${OUTPUT_FILE}"

echo "[ci-cpu-energy-estimator] output=${OUTPUT_FILE}  TDP=${TDP} W" >&2

# Parse /proc/stat first cpu line; echo "idle_jiffies total_jiffies"
read_cpu_jiffies() {
    local _label user nice system idle iowait irq softirq _rest
    read -r _label user nice system idle iowait irq softirq _rest \
        < <(grep '^cpu ' /proc/stat)
    echo "$(( idle + iowait ))  $(( user + nice + system + idle + iowait + irq + softirq ))"
}

read -r prev_idle prev_total <<< "$(read_cpu_jiffies)"

while true; do
    sleep 1

    read -r cur_idle cur_total <<< "$(read_cpu_jiffies)"

    delta_total=$(( cur_total - prev_total ))
    delta_idle=$(( cur_idle  - prev_idle  ))

    if [ "${delta_total}" -gt 0 ]; then
        power=$(awk \
            -v di="${delta_idle}" \
            -v dt="${delta_total}" \
            -v tdp="${TDP}" \
            'BEGIN {
                util = 1.0 - di / dt
                if (util < 0) util = 0
                if (util > 1) util = 1
                printf "%.3f\n", util * tdp
            }')
    else
        power="0.000"
    fi

    printf "%s\n" "${power}" > "${OUTPUT_FILE}"

    prev_idle=${cur_idle}
    prev_total=${cur_total}
done
