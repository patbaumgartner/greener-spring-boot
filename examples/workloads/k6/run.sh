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

# ── Auto-install k6 if not found ──────────────────────────────────────────────
K6_VERSION="${K6_VERSION:-0.56.0}"
if ! command -v k6 >/dev/null 2>&1; then
    echo "k6 not found — installing v${K6_VERSION} …"
    OS="$(uname -s)"
    ARCH="$(uname -m)"
    case "${OS}" in
        Linux)
            case "${ARCH}" in
                x86_64)  K6_ARCH="amd64" ;;
                aarch64) K6_ARCH="arm64" ;;
                *)       echo "[ERR] Unsupported architecture: ${ARCH}"; exit 1 ;;
            esac
            INSTALL_DIR="${HOME}/.local/bin"
            mkdir -p "${INSTALL_DIR}"
            TMP_TAR="$(mktemp).tar.gz"
            curl -fsSL -o "${TMP_TAR}" \
                "https://github.com/grafana/k6/releases/download/v${K6_VERSION}/k6-v${K6_VERSION}-linux-${K6_ARCH}.tar.gz"
            tar -xzf "${TMP_TAR}" --strip-components=1 -C "${INSTALL_DIR}" "k6-v${K6_VERSION}-linux-${K6_ARCH}/k6"
            chmod +x "${INSTALL_DIR}/k6"
            export PATH="${INSTALL_DIR}:${PATH}"
            rm -f "${TMP_TAR}"
            ;;
        Darwin)
            if command -v brew >/dev/null 2>&1; then
                brew install k6
            else
                echo "[ERR] Homebrew not found. Install k6 manually."; exit 1
            fi
            ;;
        MINGW*|MSYS*)
            INSTALL_DIR="${HOME}/.local/bin"
            mkdir -p "${INSTALL_DIR}"
            TMP_ZIP="$(mktemp).zip"
            curl -fsSL -o "${TMP_ZIP}" \
                "https://github.com/grafana/k6/releases/download/v${K6_VERSION}/k6-v${K6_VERSION}-windows-amd64.zip"
            unzip -o -j "${TMP_ZIP}" "k6-v${K6_VERSION}-windows-amd64/k6.exe" -d "${INSTALL_DIR}"
            export PATH="${INSTALL_DIR}:${PATH}"
            rm -f "${TMP_ZIP}"
            ;;
        *)  echo "[ERR] Unsupported OS: ${OS}"; exit 1 ;;
    esac
    echo "k6 installed: $(k6 version)"
fi

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
