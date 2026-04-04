#!/usr/bin/env sh
# wrk2 workload script for Spring Petclinic
#
# wrk2 uses a closed-loop constant throughput model (-R <rps>) which is
# better for reproducible energy measurements than wrk's open-loop model.
#
# Environment variables set by greener-spring-boot:
#   APP_URL         e.g. http://localhost:8080
#   WARMUP_SECONDS  warmup duration (seconds)
#   MEASURE_SECONDS measurement duration (seconds)
#   RPS             target requests per second
#
# Install wrk2: https://github.com/giltene/wrk2

set -eu

# ── Auto-install wrk2 if not found ────────────────────────────────────────────
if ! command -v wrk2 >/dev/null 2>&1; then
    echo "wrk2 not found — building from source …"
    OS="$(uname -s)"
    case "${OS}" in
        Linux)
            if command -v apt-get >/dev/null 2>&1; then
                sudo apt-get update -qq && sudo apt-get install -y -qq build-essential libssl-dev git
            fi
            ;;
        Darwin)
            if command -v brew >/dev/null 2>&1; then
                brew install openssl
            fi
            ;;
        MINGW*|MSYS*)
            echo "[ERR] wrk2 has no pre-built Windows binary. Use WSL or another tool."; exit 1
            ;;
    esac
    WRK2_SRC="$(mktemp -d)"
    git clone --depth 1 https://github.com/giltene/wrk2.git "${WRK2_SRC}"
    # Replace bundled LuaJIT with a recent version that compiles on GCC 14+
    rm -rf "${WRK2_SRC}/deps/luajit"
    git clone --depth 1 https://github.com/LuaJIT/LuaJIT.git "${WRK2_SRC}/deps/luajit"
    # Fix wrk2 sources for newer LuaJIT API (luaL_reg → luaL_Reg)
    sed -i 's/struct luaL_reg/struct luaL_Reg/g' "${WRK2_SRC}/src/script.c"
    make -C "${WRK2_SRC}" -j"$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 2)"
    INSTALL_DIR="${HOME}/.local/bin"
    mkdir -p "${INSTALL_DIR}"
    cp "${WRK2_SRC}/wrk" "${INSTALL_DIR}/wrk2"
    chmod +x "${INSTALL_DIR}/wrk2"
    export PATH="${INSTALL_DIR}:${PATH}"
    rm -rf "${WRK2_SRC}"
    echo "wrk2 installed: $(wrk2 --version 2>&1 | head -1)"
fi

APP_URL="${APP_URL:-http://localhost:8080}"
WARMUP_SECONDS="${WARMUP_SECONDS:-30}"
MEASURE_SECONDS="${MEASURE_SECONDS:-60}"
RPS="${RPS:-50}"

THREADS=$(( RPS / 10 < 2 ? 2 : RPS / 10 ))
CONNECTIONS=$(( RPS < 20 ? 20 : RPS ))
SCRIPT="$(dirname "$0")/petclinic.lua"

if [ "${WARMUP_SECONDS}" -gt 0 ]; then
    echo "=== wrk2: warmup ${WARMUP_SECONDS}s at ${RPS} req/s ==="
    wrk2 -t"${THREADS}" -c"${CONNECTIONS}" -d"${WARMUP_SECONDS}s" -R"${RPS}" \
         -s "${SCRIPT}" "${APP_URL}" || true
fi

if [ "${MEASURE_SECONDS}" -gt 0 ]; then
    echo "=== wrk2: measurement ${MEASURE_SECONDS}s at ${RPS} req/s ==="
    wrk2 -t"${THREADS}" -c"${CONNECTIONS}" -d"${MEASURE_SECONDS}s" -R"${RPS}" \
         -s "${SCRIPT}" "${APP_URL}"
fi
