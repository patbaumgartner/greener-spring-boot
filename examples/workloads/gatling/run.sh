#!/usr/bin/env sh
# Gatling workload script for Spring Petclinic
#
# This script downloads the Gatling Maven-wrapper bundle (if not already present)
# and runs the PetclinicSimulation against the target application.
#
# Gatling 3.11+ ships as a Maven project with ./mvnw; simulations are plain Java
# classes under src/test/java/.
#
# Environment variables set by greener-spring-boot:
#   APP_URL         e.g. http://localhost:8080
#   APP_HOST        e.g. localhost
#   APP_PORT        e.g. 8080
#   WARMUP_SECONDS  warmup duration (seconds)
#   MEASURE_SECONDS measurement duration (seconds)
#   RPS             target requests per second
#
# Requires: Java 17+, curl, unzip
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
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SIMULATION_SRC="${SCRIPT_DIR}/src/test/java/com/patbaumgartner/greener/simulation/PetclinicSimulation.java"

# ── 1. Download Gatling bundle if not cached ──────────────────────────────────
if [ ! -d "${GATLING_HOME}" ]; then
    echo "Downloading Gatling ${GATLING_VERSION} …"
    mkdir -p "${HOME}/.greener/cache/gatling"
    BUNDLE_URL="https://repo1.maven.org/maven2/io/gatling/highcharts/gatling-charts-highcharts-bundle/${GATLING_VERSION}/gatling-charts-highcharts-bundle-${GATLING_VERSION}.zip"
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

MVNW="${GATLING_HOME}/mvnw"
if [ ! -f "${MVNW}" ]; then
    echo "ERROR: Gatling mvnw not found at ${MVNW}" >&2
    exit 1
fi
chmod +x "${MVNW}"

# Copy our simulation into the Gatling project
DEST_DIR="${GATLING_HOME}/src/test/java/com/patbaumgartner/greener/simulation"
mkdir -p "${DEST_DIR}"
cp "${SIMULATION_SRC}" "${DEST_DIR}/"

# ── 2. Warmup phase ───────────────────────────────────────────────────────────
cd "${GATLING_HOME}"
if [ "${WARMUP_SECONDS}" -gt 0 ]; then
    echo "=== Gatling: warmup ${WARMUP_SECONDS}s at ${RPS} req/s ==="
    GATLING_APP_URL="${APP_URL}" \
    GATLING_WARMUP_SECONDS="${WARMUP_SECONDS}" \
    GATLING_MEASURE_SECONDS="0" \
    GATLING_RPS="${RPS}" \
    "${MVNW}" --batch-mode --no-transfer-progress gatling:test \
        -Dgatling.simulationClass=com.patbaumgartner.greener.simulation.PetclinicSimulation \
        -Dgatling.resultsFolder=/tmp/gatling-warmup-results \
        -Dgatling.noReports=true || true   # ignore warmup failures
fi

# ── 3. Measurement phase ──────────────────────────────────────────────────────
if [ "${MEASURE_SECONDS}" -gt 0 ]; then
    echo "=== Gatling: measurement ${MEASURE_SECONDS}s at ${RPS} req/s ==="
    GATLING_APP_URL="${APP_URL}" \
    GATLING_WARMUP_SECONDS="0" \
    GATLING_MEASURE_SECONDS="${MEASURE_SECONDS}" \
    GATLING_RPS="${RPS}" \
    "${MVNW}" --batch-mode --no-transfer-progress gatling:test \
        -Dgatling.simulationClass=com.patbaumgartner.greener.simulation.PetclinicSimulation \
        -Dgatling.resultsFolder=/tmp/gatling-measure-results

    echo "Gatling simulation complete. Reports: /tmp/gatling-measure-results"
fi
