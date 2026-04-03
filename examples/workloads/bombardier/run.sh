#!/usr/bin/env sh
# Bombardier workload script for Spring Petclinic
#
# Bombardier is a lightweight HTTP benchmarking tool written in Go.  It supports
# both requests/second rate limiting (-r) and concurrent connection control (-c),
# making it suitable for reproducible energy measurements.
#
# Like Apache Bench, Bombardier benchmarks one URL per run.  For multi-URL
# scenarios prefer wrk with a Lua script, oha with --urls-from-file, or k6.
#
# Environment variables set by greener-spring-boot:
#   APP_URL         e.g. http://localhost:8080
#   WARMUP_SECONDS  warmup duration (seconds)
#   MEASURE_SECONDS measurement duration (seconds)
#   RPS             target requests per second
#
# Install bombardier:
#   Linux/macOS (Go):  go install github.com/codesenberg/bombardier@latest
#   GitHub releases:   https://github.com/codesenberg/bombardier/releases
#   macOS (Homebrew):  brew install bombardier

set -eu

APP_URL="${APP_URL:-http://localhost:8080}"
WARMUP_SECONDS="${WARMUP_SECONDS:-30}"
MEASURE_SECONDS="${MEASURE_SECONDS:-60}"
RPS="${RPS:-20}"
CONCURRENCY="${CONCURRENCY:-5}"

BENCH_URL="${APP_URL}/"

if [ "${WARMUP_SECONDS}" -gt 0 ]; then
    echo "=== bombardier: warmup ${WARMUP_SECONDS}s at ${RPS} req/s ==="
    bombardier \
        --connections "${CONCURRENCY}" \
        --rate "${RPS}" \
        --duration "${WARMUP_SECONDS}s" \
        --print r \
        "${BENCH_URL}" || true
fi

echo "=== bombardier: measurement ${MEASURE_SECONDS}s at ${RPS} req/s ==="
bombardier \
    --connections "${CONCURRENCY}" \
    --rate "${RPS}" \
    --duration "${MEASURE_SECONDS}s" \
    --print r \
    "${BENCH_URL}"
