#!/usr/bin/env sh
# wrk workload script for Spring Petclinic
#
# Environment variables set by greener-spring-boot:
#   APP_URL         e.g. http://localhost:8080
#   WARMUP_SECONDS  warmup duration (seconds)
#   MEASURE_SECONDS measurement duration (seconds)
#   RPS             target requests per second (used to derive -c / -t)
#
# Install wrk: https://github.com/wg/wrk
#   Linux:  sudo apt-get install wrk
#   macOS:  brew install wrk

set -eu

APP_URL="${APP_URL:-http://localhost:8080}"
WARMUP_SECONDS="${WARMUP_SECONDS:-30}"
MEASURE_SECONDS="${MEASURE_SECONDS:-60}"
RPS="${RPS:-50}"

# Derive connection / thread count from RPS (rough heuristic)
THREADS=$(( RPS / 10 < 2 ? 2 : RPS / 10 ))
CONNECTIONS="${RPS}"
SCRIPT="$(dirname "$0")/petclinic.lua"

echo "=== wrk: warmup ${WARMUP_SECONDS}s ==="
wrk -t"${THREADS}" -c"${CONNECTIONS}" -d"${WARMUP_SECONDS}s" \
    -s "${SCRIPT}" "${APP_URL}" || true   # ignore warmup failures

echo "=== wrk: measurement ${MEASURE_SECONDS}s ==="
wrk -t"${THREADS}" -c"${CONNECTIONS}" -d"${MEASURE_SECONDS}s" \
    -s "${SCRIPT}" "${APP_URL}"
