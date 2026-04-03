#!/usr/bin/env sh
# Gatling workload script for Spring Petclinic
#
# This script downloads the Gatling bundle (if not already present) and runs
# the PetclinicSimulation against the target application.
#
# Environment variables set by greener-spring-boot:
#   APP_URL         e.g. http://localhost:8080
#   APP_HOST        e.g. localhost
#   APP_PORT        e.g. 8080
#   WARMUP_SECONDS  warmup duration (seconds)
#   MEASURE_SECONDS measurement duration (seconds)
#   RPS             target requests per second
#
# Requires: Java 17+, curl, unzip/tar
# Documentation: https://gatling.io/docs/gatling/reference/current/

set -eu

APP_URL="${APP_URL:-http://localhost:8080}"
APP_HOST="${APP_HOST:-localhost}"
APP_PORT="${APP_PORT:-8080}"
WARMUP_SECONDS="${WARMUP_SECONDS:-30}"
MEASURE_SECONDS="${MEASURE_SECONDS:-60}"
RPS="${RPS:-10}"

GATLING_VERSION="3.11.5"
GATLING_HOME="${HOME}/.greener/cache/gatling/gatling-${GATLING_VERSION}"
SIMULATION_DIR="$(dirname "$0")/src/test/scala"

# ── 1. Download Gatling bundle if not cached ──────────────────────────────────
if [ ! -d "${GATLING_HOME}" ]; then
    echo "Downloading Gatling ${GATLING_VERSION} …"
    mkdir -p "${HOME}/.greener/cache/gatling"
    BUNDLE_URL="https://repo1.maven.org/maven2/io/gatling/highcharts/gatling-charts-highcharts-bundle/${GATLING_VERSION}/gatling-charts-highcharts-bundle-${GATLING_VERSION}-bundle.zip"
    TMP_ZIP="$(mktemp).zip"
    curl -fsSL -o "${TMP_ZIP}" "${BUNDLE_URL}"
    unzip -q "${TMP_ZIP}" -d "${HOME}/.greener/cache/gatling"
    rm -f "${TMP_ZIP}"
    EXTRACTED="$(ls -d "${HOME}/.greener/cache/gatling/gatling-charts-highcharts-bundle-${GATLING_VERSION}" 2>/dev/null || true)"
    if [ -n "${EXTRACTED}" ] && [ "${EXTRACTED}" != "${GATLING_HOME}" ]; then
        mv "${EXTRACTED}" "${GATLING_HOME}"
    fi
    echo "Gatling installed to ${GATLING_HOME}"
fi

GATLING_BIN="${GATLING_HOME}/bin/gatling.sh"
if [ ! -f "${GATLING_BIN}" ]; then
    echo "ERROR: Gatling binary not found at ${GATLING_BIN}" >&2
    exit 1
fi

# Copy simulation to Gatling's user-files directory
GATLING_USER_SIMULATIONS="${GATLING_HOME}/user-files/simulations"
mkdir -p "${GATLING_USER_SIMULATIONS}"
cp "${SIMULATION_DIR}/com/patbaumgartner/greener/simulation/PetclinicSimulation.scala" \
   "${GATLING_USER_SIMULATIONS}/"

# ── 2. Warmup phase ───────────────────────────────────────────────────────────
echo "=== Gatling: warmup ${WARMUP_SECONDS}s at ${RPS} req/s ==="
GATLING_APP_URL="${APP_URL}" \
GATLING_WARMUP_SECONDS="${WARMUP_SECONDS}" \
GATLING_MEASURE_SECONDS="0" \
GATLING_RPS="${RPS}" \
"${GATLING_BIN}" \
    --simulation PetclinicSimulation \
    --results-folder /tmp/gatling-warmup-results \
    --no-reports || true   # ignore warmup failures

# ── 3. Measurement phase ──────────────────────────────────────────────────────
echo "=== Gatling: measurement ${MEASURE_SECONDS}s at ${RPS} req/s ==="
GATLING_APP_URL="${APP_URL}" \
GATLING_WARMUP_SECONDS="0" \
GATLING_MEASURE_SECONDS="${MEASURE_SECONDS}" \
GATLING_RPS="${RPS}" \
"${GATLING_BIN}" \
    --simulation PetclinicSimulation \
    --results-folder /tmp/gatling-measure-results

echo "Gatling simulation complete. Reports: /tmp/gatling-measure-results"
