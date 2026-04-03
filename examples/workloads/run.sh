#!/usr/bin/env sh
# Run all workload examples against a running Spring Petclinic instance.
#
# This script iterates over every tool directory, checks whether the tool's
# binary is available, and runs its run.sh.  Tools that are not installed are
# skipped.  At the end a summary table is printed.
#
# Usage:
#   # Start Petclinic first, then:
#   ./examples/workloads/run.sh
#
#   # Override defaults:
#   APP_URL=http://localhost:9090 WARMUP_SECONDS=10 MEASURE_SECONDS=30 RPS=20 \
#     ./examples/workloads/run.sh
#
# Environment variables (all optional — sensible defaults are used):
#   APP_URL          Base URL              (default: http://localhost:8080)
#   APP_HOST         Host name             (default: localhost)
#   APP_PORT         Port                  (default: 8080)
#   WARMUP_SECONDS   Warmup duration       (default: 5)
#   MEASURE_SECONDS  Measurement duration  (default: 15)
#   RPS              Requests per second   (default: 10)

set -eu

# ── Defaults ──────────────────────────────────────────────────────────────────
APP_URL="${APP_URL:-http://localhost:8080}"
APP_HOST="${APP_HOST:-localhost}"
APP_PORT="${APP_PORT:-8080}"
WARMUP_SECONDS="${WARMUP_SECONDS:-5}"
MEASURE_SECONDS="${MEASURE_SECONDS:-15}"
RPS="${RPS:-10}"
TOTAL_SECONDS=$((WARMUP_SECONDS + MEASURE_SECONDS))

export APP_URL APP_HOST APP_PORT WARMUP_SECONDS MEASURE_SECONDS RPS TOTAL_SECONDS

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ── Tool detection ────────────────────────────────────────────────────────────
# Maps each directory name to the binary that must be on PATH.
tool_binary() {
    case "$1" in
        oha)        echo "oha" ;;
        wrk)        echo "wrk" ;;
        wrk2)       echo "wrk2" ;;
        k6)         echo "k6" ;;
        ab)         echo "ab" ;;
        bombardier) echo "bombardier" ;;
        locust)     echo "locust" ;;
        gatling)    echo "java" ;;   # Gatling run.sh downloads the bundle; only needs Java
        *)          echo "" ;;
    esac
}

TOOLS="oha wrk wrk2 k6 ab bombardier locust gatling"
PASSED=""
FAILED=""
SKIPPED=""

# ── Pre-flight: is the app reachable? ─────────────────────────────────────────
echo "═══════════════════════════════════════════════════════════════════════"
echo "  Workload Validation — all tools"
echo "  Target: ${APP_URL}   Warmup: ${WARMUP_SECONDS}s   Measure: ${MEASURE_SECONDS}s   RPS: ${RPS}"
echo "═══════════════════════════════════════════════════════════════════════"
echo ""

if ! curl -sf "${APP_URL}/actuator/health" >/dev/null 2>&1; then
    echo "⚠  WARNING: ${APP_URL}/actuator/health is not responding."
    echo "   Make sure Spring Petclinic (or your app) is running before executing this script."
    echo ""
fi

# ── Run each tool ─────────────────────────────────────────────────────────────
for tool in ${TOOLS}; do
    BIN="$(tool_binary "${tool}")"

    if [ -z "${BIN}" ] || ! command -v "${BIN}" >/dev/null 2>&1; then
        echo "── ${tool}: SKIPPED (${BIN:-binary} not found) ──────────────────────"
        SKIPPED="${SKIPPED} ${tool}"
        echo ""
        continue
    fi

    TOOL_SCRIPT="${SCRIPT_DIR}/${tool}/run.sh"
    if [ ! -f "${TOOL_SCRIPT}" ]; then
        echo "── ${tool}: SKIPPED (run.sh not found) ──────────────────────────────"
        SKIPPED="${SKIPPED} ${tool}"
        echo ""
        continue
    fi

    echo "── ${tool}: RUNNING ─────────────────────────────────────────────────"
    chmod +x "${TOOL_SCRIPT}"
    if sh "${TOOL_SCRIPT}"; then
        PASSED="${PASSED} ${tool}"
        echo "── ${tool}: PASSED ──────────────────────────────────────────────────"
    else
        FAILED="${FAILED} ${tool}"
        echo "── ${tool}: FAILED ──────────────────────────────────────────────────"
    fi
    echo ""
done

# ── Summary ───────────────────────────────────────────────────────────────────
echo "═══════════════════════════════════════════════════════════════════════"
echo "  Summary"
echo "═══════════════════════════════════════════════════════════════════════"

PASS_COUNT=0; FAIL_COUNT=0; SKIP_COUNT=0
for t in ${PASSED}; do PASS_COUNT=$((PASS_COUNT + 1)); done
for t in ${FAILED}; do FAIL_COUNT=$((FAIL_COUNT + 1)); done
for t in ${SKIPPED}; do SKIP_COUNT=$((SKIP_COUNT + 1)); done

[ -n "${PASSED}" ]  && echo "  ✅ Passed  (${PASS_COUNT}):${PASSED}"
[ -n "${FAILED}" ]  && echo "  ❌ Failed  (${FAIL_COUNT}):${FAILED}"
[ -n "${SKIPPED}" ] && echo "  ⏭  Skipped (${SKIP_COUNT}):${SKIPPED}"
echo ""

if [ "${FAIL_COUNT}" -gt 0 ]; then
    echo "  Result: ${FAIL_COUNT} tool(s) failed."
    exit 1
fi

echo "  Result: All executed tools passed."
