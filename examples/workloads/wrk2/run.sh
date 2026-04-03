#!/usr/bin/env sh
# wrk2 workload script for Spring Petclinic
#
# wrk2 uses a closed-loop constant throughput model (-R <rps>) which is
# better for reproducible energy measurements than wrk's open-loop model.
#
# Environment variables set by greener-spring-boot:
#   APP_URL         e.g. http://localhost:8080
#   WARMUP_SECONDS  warmup duration (seconds)
#   MEASURE_SECONDS measurement duration (seconds)
#   RPS             target requests per second
#
# Install wrk2: https://github.com/giltene/wrk2

set -eu

APP_URL="${APP_URL:-http://localhost:8080}"
WARMUP_SECONDS="${WARMUP_SECONDS:-30}"
MEASURE_SECONDS="${MEASURE_SECONDS:-60}"
RPS="${RPS:-50}"

THREADS=$(( RPS / 10 < 2 ? 2 : RPS / 10 ))
CONNECTIONS=$(( RPS < 20 ? 20 : RPS ))
SCRIPT="$(dirname "$0")/petclinic.lua"

if [ "${WARMUP_SECONDS}" -gt 0 ]; then
    echo "=== wrk2: warmup ${WARMUP_SECONDS}s at ${RPS} req/s ==="
    wrk2 -t"${THREADS}" -c"${CONNECTIONS}" -d"${WARMUP_SECONDS}s" -R"${RPS}" \
         -s "${SCRIPT}" "${APP_URL}" || true
fi

if [ "${MEASURE_SECONDS}" -gt 0 ]; then
    echo "=== wrk2: measurement ${MEASURE_SECONDS}s at ${RPS} req/s ==="
    wrk2 -t"${THREADS}" -c"${CONNECTIONS}" -d"${MEASURE_SECONDS}s" -R"${RPS}" \
         -s "${SCRIPT}" "${APP_URL}"
fi
