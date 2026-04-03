#!/usr/bin/env sh
# k6 workload script for Spring Petclinic
#
# k6 uses a constant-arrival-rate executor which is ideal for energy measurement:
# the request rate is independent of server response time, giving reproducible load.
#
# Environment variables set by greener-spring-boot:
#   APP_URL         e.g. http://localhost:8080
#   APP_HOST        e.g. localhost
#   APP_PORT        e.g. 8080
#   WARMUP_SECONDS  warmup duration (seconds)
#   MEASURE_SECONDS measurement duration (seconds)
#   TOTAL_SECONDS   total duration
#   RPS             target requests per second
#
# Install k6:
#   Linux:  sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg \
#             --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
#           echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] \
#             https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
#           sudo apt-get update && sudo apt-get install k6
#   macOS:  brew install k6
#   Docker: docker run --rm -i grafana/k6 run -
#   Docs:   https://grafana.com/docs/k6/latest/

set -eu

APP_URL="${APP_URL:-http://localhost:8080}"
WARMUP_SECONDS="${WARMUP_SECONDS:-30}"
MEASURE_SECONDS="${MEASURE_SECONDS:-60}"
RPS="${RPS:-20}"

SCRIPT="$(dirname "$0")/petclinic.js"

echo "=== k6: warmup=${WARMUP_SECONDS}s + measurement=${MEASURE_SECONDS}s at ${RPS} req/s ==="

APP_URL="${APP_URL}" \
WARMUP_SECONDS="${WARMUP_SECONDS}" \
MEASURE_SECONDS="${MEASURE_SECONDS}" \
RPS="${RPS}" \
k6 run "${SCRIPT}"
