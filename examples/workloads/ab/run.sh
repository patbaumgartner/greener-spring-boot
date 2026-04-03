#!/usr/bin/env sh
# Apache Bench (ab) workload script for Spring Petclinic
#
# ab is the simplest possible HTTP load generator — it ships with every Apache
# installation and most Linux distributions.  It has no native warmup support,
# so this script runs two sequential ab invocations: one for warmup and one for
# the measurement window.
#
# Limitations: ab is single-threaded and supports only one URL per run.  For
# multi-URL scenarios use wrk, oha, or k6 instead.  It is included here because
# it is universally available without any extra installation.
#
# Environment variables set by greener-spring-boot:
#   APP_URL         e.g. http://localhost:8080
#   WARMUP_SECONDS  warmup duration (seconds)
#   MEASURE_SECONDS measurement duration (seconds)
#   RPS             target requests per second
#
# Install ab:
#   Linux (Debian/Ubuntu):  sudo apt-get install apache2-utils
#   macOS:                  already installed (part of macOS)
#   RHEL/CentOS:            sudo yum install httpd-tools

set -eu

APP_URL="${APP_URL:-http://localhost:8080}"
WARMUP_SECONDS="${WARMUP_SECONDS:-30}"
MEASURE_SECONDS="${MEASURE_SECONDS:-60}"
RPS="${RPS:-20}"
CONCURRENCY="${CONCURRENCY:-5}"

# Derive total request counts from RPS × duration (ab uses -n for total requests)
WARMUP_REQUESTS=$(( RPS * WARMUP_SECONDS ))
MEASURE_REQUESTS=$(( RPS * MEASURE_SECONDS ))

# ab requires a trailing slash on the path
BENCH_URL="${APP_URL}/"

if [ "${WARMUP_SECONDS}" -gt 0 ] && [ "${WARMUP_REQUESTS}" -gt 0 ]; then
    echo "=== ab: warmup — ${WARMUP_REQUESTS} requests at ${RPS} req/s ==="
    ab -n "${WARMUP_REQUESTS}" -c "${CONCURRENCY}" -q "${BENCH_URL}" || true
fi

echo "=== ab: measurement — ${MEASURE_REQUESTS} requests at ${RPS} req/s ==="
ab -n "${MEASURE_REQUESTS}" -c "${CONCURRENCY}" "${BENCH_URL}"
