#!/usr/bin/env sh
# oha workload script for Spring Petclinic
#
# oha is a tiny, fast HTTP load generator written in Rust with a real-time TUI.
# It is well-suited for energy measurement because it produces consistent,
# constant-rate load with low overhead.
#
# Environment variables set by greener-spring-boot:
#   APP_URL         e.g. http://localhost:8080
#   APP_HOST        e.g. localhost
#   APP_PORT        e.g. 8080
#   WARMUP_SECONDS  warmup duration (seconds)
#   MEASURE_SECONDS measurement duration (seconds)
#   RPS             target requests per second
#
# Install oha:
#   Linux/macOS (cargo): cargo install oha
#   macOS (Homebrew):    brew install oha
#   GitHub releases:     https://github.com/hatoo/oha/releases

set -eu

APP_URL="${APP_URL:-http://localhost:8080}"
WARMUP_SECONDS="${WARMUP_SECONDS:-30}"
MEASURE_SECONDS="${MEASURE_SECONDS:-60}"
RPS="${RPS:-50}"

PATHS="/
/owners
/owners?lastName=
/vets.html
/actuator/health"

echo "=== oha: warmup ${WARMUP_SECONDS}s at ${RPS} req/s ==="
oha --no-tui \
    -z "${WARMUP_SECONDS}s" \
    -q "${RPS}" \
    "${APP_URL}/" || true

echo "=== oha: measurement ${MEASURE_SECONDS}s at ${RPS} req/s ==="
oha --no-tui \
    -z "${MEASURE_SECONDS}s" \
    -q "${RPS}" \
    "${APP_URL}/"
