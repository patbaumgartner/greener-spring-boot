#!/usr/bin/env sh
# Locust workload script for Spring Petclinic
#
# Locust runs headless (no browser) and controls the user count so that the
# overall throughput matches the configured RPS value.
#
# Environment variables set by greener-spring-boot:
#   APP_URL         e.g. http://localhost:8080
#   WARMUP_SECONDS  warmup duration (seconds)
#   MEASURE_SECONDS measurement duration (seconds)
#   RPS             target requests per second
#
# Install:
#   pip install locust
#   Docs: https://locust.io

set -eu

APP_URL="${APP_URL:-http://localhost:8080}"
WARMUP_SECONDS="${WARMUP_SECONDS:-30}"
MEASURE_SECONDS="${MEASURE_SECONDS:-60}"
RPS="${RPS:-20}"

SCRIPT="$(dirname "$0")/locustfile.py"

# Each PetclinicUser issues ~1 req/s, so USERS ≈ RPS
USERS="${RPS}"
SPAWN_RATE="${RPS}"   # ramp up all users in 1 second

if [ "${WARMUP_SECONDS}" -gt 0 ]; then
    echo "=== Locust: warmup ${WARMUP_SECONDS}s — ${USERS} users at ${RPS} req/s ==="
    APP_URL="${APP_URL}" RPS="${RPS}" \
    locust --headless \
        --locustfile "${SCRIPT}" \
        --host "${APP_URL}" \
        --users "${USERS}" \
        --spawn-rate "${SPAWN_RATE}" \
        --run-time "${WARMUP_SECONDS}s" \
        --stop-timeout 5 \
        --exit-code-on-error 0 || true
fi

echo "=== Locust: measurement ${MEASURE_SECONDS}s — ${USERS} users at ${RPS} req/s ==="
APP_URL="${APP_URL}" RPS="${RPS}" \
locust --headless \
    --locustfile "${SCRIPT}" \
    --host "${APP_URL}" \
    --users "${USERS}" \
    --spawn-rate "${SPAWN_RATE}" \
    --run-time "${MEASURE_SECONDS}s" \
    --stop-timeout 5
