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

# ── Auto-install bombardier if not found ──────────────────────────────────────
BOMBARDIER_VERSION="${BOMBARDIER_VERSION:-1.2.6}"
if ! command -v bombardier >/dev/null 2>&1; then
    echo "bombardier not found — installing ${BOMBARDIER_VERSION} …"
    OS="$(uname -s)"
    ARCH="$(uname -m)"
    case "${OS}" in
        Linux)
            case "${ARCH}" in
                x86_64)  ASSET="bombardier-linux-amd64" ;;
                aarch64) ASSET="bombardier-linux-arm64" ;;
                *)       echo "[ERR] Unsupported architecture: ${ARCH}"; exit 1 ;;
            esac
            INSTALL_DIR="${HOME}/.local/bin"
            mkdir -p "${INSTALL_DIR}"
            curl -fsSL -o "${INSTALL_DIR}/bombardier" \
                "https://github.com/codesenberg/bombardier/releases/download/v${BOMBARDIER_VERSION}/${ASSET}"
            chmod +x "${INSTALL_DIR}/bombardier"
            export PATH="${INSTALL_DIR}:${PATH}"
            ;;
        Darwin)
            if command -v brew >/dev/null 2>&1; then
                brew install bombardier
            else
                echo "[ERR] Homebrew not found. Install bombardier manually."; exit 1
            fi
            ;;
        MINGW*|MSYS*)
            INSTALL_DIR="${HOME}/.local/bin"
            mkdir -p "${INSTALL_DIR}"
            curl -fsSL -o "${INSTALL_DIR}/bombardier.exe" \
                "https://github.com/codesenberg/bombardier/releases/download/v${BOMBARDIER_VERSION}/bombardier-windows-amd64.exe"
            export PATH="${INSTALL_DIR}:${PATH}"
            ;;
        *)  echo "[ERR] Unsupported OS: ${OS}"; exit 1 ;;
    esac
    echo "bombardier installed: $(bombardier --version 2>&1 | head -1)"
fi

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

if [ "${MEASURE_SECONDS}" -gt 0 ]; then
    echo "=== bombardier: measurement ${MEASURE_SECONDS}s at ${RPS} req/s ==="
    bombardier \
        --connections "${CONCURRENCY}" \
        --rate "${RPS}" \
        --duration "${MEASURE_SECONDS}s" \
        --print r \
        "${BENCH_URL}"
fi
