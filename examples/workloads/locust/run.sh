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

# ── Auto-install locust if not found ──────────────────────────────────────────
if ! command -v locust >/dev/null 2>&1; then
    echo "locust not found — installing via pip …"
    OS="$(uname -s)"
    case "${OS}" in
        MINGW*|MSYS*)
            if command -v pip >/dev/null 2>&1; then
                pip install --quiet locust
            elif command -v pip3 >/dev/null 2>&1; then
                pip3 install --quiet locust
            else
                echo "[ERR] pip not found. Install Python 3 and pip first."; exit 1
            fi
            ;;
        *)
            # Use a virtual environment to avoid PEP 668 restrictions on modern distros
            VENV_DIR="${HOME}/.greener/venvs/locust"
            if [ ! -d "${VENV_DIR}" ]; then
                python3 -m venv "${VENV_DIR}"
            fi
            "${VENV_DIR}/bin/pip" install --quiet locust
            export PATH="${VENV_DIR}/bin:${PATH}"
            ;;
    esac
    echo "locust installed: $(locust --version)"
fi

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

if [ "${MEASURE_SECONDS}" -gt 0 ]; then
    echo "=== Locust: measurement ${MEASURE_SECONDS}s — ${USERS} users at ${RPS} req/s ==="
    APP_URL="${APP_URL}" RPS="${RPS}" \
    locust --headless \
        --locustfile "${SCRIPT}" \
        --host "${APP_URL}" \
        --users "${USERS}" \
        --spawn-rate "${SPAWN_RATE}" \
        --run-time "${MEASURE_SECONDS}s" \
        --stop-timeout 5
fi
