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

# ── Auto-install wrk if not found ─────────────────────────────────────────────
if ! command -v wrk >/dev/null 2>&1; then
    echo "wrk not found — installing …"
    OS="$(uname -s)"
    case "${OS}" in
        Linux)
            if command -v apt-get >/dev/null 2>&1; then
                sudo apt-get update -qq && sudo apt-get install -y -qq wrk
            else
                echo "[ERR] apt-get not found. Install wrk manually."; exit 1
            fi
            ;;
        Darwin)
            if command -v brew >/dev/null 2>&1; then
                brew install wrk
            else
                echo "[ERR] Homebrew not found. Install wrk manually."; exit 1
            fi
            ;;
        MINGW*|MSYS*)
            echo "[ERR] wrk has no pre-built Windows binary. Use WSL or another tool."; exit 1
            ;;
        *)  echo "[ERR] Unsupported OS: ${OS}"; exit 1 ;;
    esac
    echo "wrk installed: $(wrk --version 2>&1 | head -1)"
fi

APP_URL="${APP_URL:-http://localhost:8080}"
WARMUP_SECONDS="${WARMUP_SECONDS:-30}"
MEASURE_SECONDS="${MEASURE_SECONDS:-60}"
RPS="${RPS:-50}"

# Derive connection / thread count from RPS (rough heuristic)
THREADS=$(( RPS / 10 < 2 ? 2 : RPS / 10 ))
CONNECTIONS="${RPS}"
SCRIPT="$(dirname "$0")/petclinic.lua"

if [ "${WARMUP_SECONDS}" -gt 0 ]; then
    echo "=== wrk: warmup ${WARMUP_SECONDS}s ==="
    wrk -t"${THREADS}" -c"${CONNECTIONS}" -d"${WARMUP_SECONDS}s" \
        -s "${SCRIPT}" "${APP_URL}" || true   # ignore warmup failures
fi

if [ "${MEASURE_SECONDS}" -gt 0 ]; then
    echo "=== wrk: measurement ${MEASURE_SECONDS}s ==="
    wrk -t"${THREADS}" -c"${CONNECTIONS}" -d"${MEASURE_SECONDS}s" \
        -s "${SCRIPT}" "${APP_URL}"
fi
