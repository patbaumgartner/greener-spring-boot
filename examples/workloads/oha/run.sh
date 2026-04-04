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

# ── Auto-install oha if not found ─────────────────────────────────────────────
OHA_VERSION="${OHA_VERSION:-1.14.0}"
if ! command -v oha >/dev/null 2>&1; then
    echo "oha not found — installing ${OHA_VERSION} …"
    OS="$(uname -s)"
    ARCH="$(uname -m)"
    case "${OS}" in
        Linux)
            case "${ARCH}" in
                x86_64)  ASSET="oha-linux-amd64" ;;
                aarch64) ASSET="oha-linux-aarch64" ;;
                *)       echo "[ERR] Unsupported architecture: ${ARCH}"; exit 1 ;;
            esac
            INSTALL_DIR="${HOME}/.local/bin"
            mkdir -p "${INSTALL_DIR}"
            curl -fsSL -o "${INSTALL_DIR}/oha" \
                "https://github.com/hatoo/oha/releases/download/v${OHA_VERSION}/${ASSET}"
            chmod +x "${INSTALL_DIR}/oha"
            export PATH="${INSTALL_DIR}:${PATH}"
            ;;
        Darwin)
            if command -v brew >/dev/null 2>&1; then
                brew install oha
            else
                echo "[ERR] Homebrew not found. Install oha manually."; exit 1
            fi
            ;;
        MINGW*|MSYS*)
            INSTALL_DIR="${HOME}/.local/bin"
            mkdir -p "${INSTALL_DIR}"
            curl -fsSL -o "${INSTALL_DIR}/oha.exe" \
                "https://github.com/hatoo/oha/releases/download/v${OHA_VERSION}/oha-windows-amd64.exe"
            export PATH="${INSTALL_DIR}:${PATH}"
            ;;
        *)  echo "[ERR] Unsupported OS: ${OS}"; exit 1 ;;
    esac
    echo "oha installed: $(oha --version)"
fi

APP_URL="${APP_URL:-http://localhost:8080}"
WARMUP_SECONDS="${WARMUP_SECONDS:-30}"
MEASURE_SECONDS="${MEASURE_SECONDS:-60}"
RPS="${RPS:-50}"

PATHS="/
/owners
/owners?lastName=
/vets.html
/actuator/health"

if [ "${WARMUP_SECONDS}" -gt 0 ]; then
    echo "=== oha: warmup ${WARMUP_SECONDS}s at ${RPS} req/s ==="
    oha --no-tui \
        -z "${WARMUP_SECONDS}s" \
        -q "${RPS}" \
        "${APP_URL}/" || true
fi

if [ "${MEASURE_SECONDS}" -gt 0 ]; then
    echo "=== oha: measurement ${MEASURE_SECONDS}s at ${RPS} req/s ==="
    oha --no-tui \
        -z "${MEASURE_SECONDS}s" \
        -q "${RPS}" \
        "${APP_URL}/"
fi
