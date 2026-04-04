#!/usr/bin/env bash
# all-tools-simulation.sh
#
# Runs an energy measurement for every installed workload tool against
# Spring Petclinic, then prints a summary comparison across all tools.
#
# This is the multi-tool counterpart of local-simulation.sh.  Instead of
# running a single tool twice (baseline + comparison), it runs every
# detected tool once and collects the per-tool energy reports so they can
# be compared side-by-side.
#
# Supported tools (skipped automatically if not installed):
#   oha, wrk, wrk2, bombardier, ab, k6, gatling, locust
#
# Power source auto-detection (same four tiers as local-simulation.sh):
#   1. RAPL          — bare-metal Linux with readable powercap
#   2. WSL + RAPL    — Hubblo RAPL driver on Windows host (CPU x TDP fallback)
#   3. Scaphandre VM — KVM guest with host-side power file
#   4. CPU × TDP     — software estimation via /proc/stat
#
# Prerequisites:
#   - Java 17+, Maven, git, curl
#   - At least one workload tool installed (oha, wrk, …)
#
# Usage:
#   ./examples/all-tools-simulation.sh
#
# Environment variables (all optional — sensible defaults are used):
#   PETCLINIC_VERSION      Branch/tag to clone             (default: main)
#   JOULAR_CORE_VERSION    Joular Core release tag         (default: 0.0.1-alpha-11)
#   MEASURE_SECONDS        Measurement duration            (default: 60)
#   WARMUP_SECONDS         Warmup duration                 (default: 30)
#   THRESHOLD              Regression threshold in %       (default: 10)
#   TDP_WATTS              TDP for CPU estimation          (default: 100)
#   VM_POWER_FILE          Scaphandre VM power file path   (default: unset)
#   WORK_DIR               Temporary working directory     (default: /tmp/greener-all-tools)

set -euo pipefail

# ── Configuration ─────────────────────────────────────────────────────────────
PETCLINIC_VERSION="${PETCLINIC_VERSION:-main}"
JOULAR_CORE_VERSION="${JOULAR_CORE_VERSION:-0.0.1-alpha-11}"
MEASURE_SECONDS="${MEASURE_SECONDS:-60}"
WARMUP_SECONDS="${WARMUP_SECONDS:-30}"
THRESHOLD="${THRESHOLD:-10}"
TDP_WATTS="${TDP_WATTS:-100}"
WORK_DIR="${WORK_DIR:-/tmp/greener-all-tools}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

PETCLINIC_DIR="${WORK_DIR}/spring-petclinic"
CI_POWER_FILE="${WORK_DIR}/ci-power.txt"
JOULAR_CACHE_DIR="${HOME}/.greener/cache/joularcore"

CI_ESTIMATOR_PID=""

# ── Tool list & binary mapping ────────────────────────────────────────────────
TOOLS="oha wrk wrk2 bombardier ab k6 locust gatling"

tool_binary() {
    case "$1" in
        oha)        echo "oha" ;;
        wrk)        echo "wrk" ;;
        wrk2)       echo "wrk2" ;;
        k6)         echo "k6" ;;
        ab)         echo "ab" ;;
        bombardier) echo "bombardier" ;;
        locust)     echo "locust" ;;
        gatling)    echo "java" ;;
        *)          echo "" ;;
    esac
}

# ── Helpers ───────────────────────────────────────────────────────────────────
info()  { echo "i  $*"; }
ok()    { echo "[OK] $*"; }
warn()  { echo "[!!] $*"; }
banner() {
    echo ""
    echo "============================================================"
    echo "  $*"
    echo "============================================================"
}

cleanup() {
    if [ -n "${CI_ESTIMATOR_PID}" ]; then
        kill "${CI_ESTIMATOR_PID}" 2>/dev/null || true
        info "Stopped CI power estimator (PID ${CI_ESTIMATOR_PID})"
    fi
}
trap cleanup EXIT

# ── Preflight checks ─────────────────────────────────────────────────────────
banner "Preflight checks"

command -v java  >/dev/null 2>&1 || { echo "[ERR] java not found. Install JDK 17+."; exit 1; }
command -v mvn   >/dev/null 2>&1 || { echo "[ERR] mvn not found. Install Maven."; exit 1; }

for tool in git curl; do
    command -v "${tool}" >/dev/null 2>&1 || { echo "[ERR] ${tool} not found."; exit 1; }
done

java -version 2>&1 | head -1
mvn --version 2>&1 | head -1

mkdir -p "${WORK_DIR}"

# Detect available tools
AVAILABLE_TOOLS=""
for tool in ${TOOLS}; do
    BIN="$(tool_binary "${tool}")"
    if [ -n "${BIN}" ] && command -v "${BIN}" >/dev/null 2>&1; then
        AVAILABLE_TOOLS="${AVAILABLE_TOOLS} ${tool}"
    fi
done
AVAILABLE_TOOLS="${AVAILABLE_TOOLS# }"

if [ -z "${AVAILABLE_TOOLS}" ]; then
    echo "[ERR] No workload tools found. Install at least one of: ${TOOLS}"
    exit 1
fi

ok "Available tools: ${AVAILABLE_TOOLS}"

# ── Build greener-spring-boot plugins ─────────────────────────────────────────
banner "Building greener-spring-boot plugins"

cd "${PROJECT_ROOT}"
mvn --batch-mode --no-transfer-progress clean install -DskipTests

# ── Clone & build Spring Petclinic ────────────────────────────────────────────
banner "Cloning Spring Petclinic (${PETCLINIC_VERSION})"

if [ -d "${PETCLINIC_DIR}" ]; then
    info "Removing existing Petclinic clone"
    rm -rf "${PETCLINIC_DIR}"
fi

git clone --depth 1 --branch "${PETCLINIC_VERSION}" \
    https://github.com/spring-projects/spring-petclinic.git \
    "${PETCLINIC_DIR}"

banner "Building Spring Petclinic"
cd "${PETCLINIC_DIR}"
mvn --batch-mode --no-transfer-progress package -DskipTests

# ── Copy workload scripts ────────────────────────────────────────────────────
cp -r "${PROJECT_ROOT}/examples" "${PETCLINIC_DIR}/"

# ── Power source detection ───────────────────────────────────────────────────
banner "Detecting power source"

POWER_SOURCE="none"
IS_WSL=false
if grep -qi microsoft /proc/version 2>/dev/null; then
    IS_WSL=true
fi

if [ -r /sys/class/powercap/intel-rapl/intel-rapl:0/energy_uj ]; then
    POWER_SOURCE="rapl"
    ok "RAPL readable — hardware energy measurement."
elif [ "$IS_WSL" = true ] && sc.exe query ScaphandreDrv 2>/dev/null | grep -q RUNNING 2>/dev/null; then
    POWER_SOURCE="ci-estimated"
    ok "WSL: Hubblo RAPL driver (ScaphandreDrv) detected on Windows host."
elif [ -n "${VM_POWER_FILE:-}" ] && [ -f "${VM_POWER_FILE}" ]; then
    POWER_SOURCE="vm-file"
    ok "Scaphandre VM power file found."
elif [ -f /proc/stat ]; then
    POWER_SOURCE="ci-estimated"
    info "Using CPU-time × TDP software estimation."
else
    warn "No power source — measurement will be skipped."
fi

if [ "${POWER_SOURCE}" = "none" ]; then
    echo ""
    echo "No usable power source detected. Exiting."
    exit 0
fi

# ── Start CI CPU power estimator (if needed) ──────────────────────────────────
if [ "${POWER_SOURCE}" = "ci-estimated" ]; then
    info "Starting CI CPU power estimator (TDP=${TDP_WATTS} W)..."
    bash "${PROJECT_ROOT}/examples/vm-setup/ci-cpu-energy-estimator.sh" \
        "${CI_POWER_FILE}" "${TDP_WATTS}" &
    CI_ESTIMATOR_PID=$!
    export VM_POWER_FILE="${CI_POWER_FILE}"
    sleep 2
    info "Estimator running — current estimate: $(cat "${CI_POWER_FILE}") W"
fi

# ── Joular Core ──────────────────────────────────────────────────────────────
banner "Preparing Joular Core ${JOULAR_CORE_VERSION}"

ARCH="$(uname -m)"
case "${ARCH}" in
    x86_64)  JOULAR_ARCH="x86_64" ;;
    aarch64) JOULAR_ARCH="aarch64" ;;
    *)       echo "[ERR] Unsupported architecture: ${ARCH}"; exit 1 ;;
esac
JOULAR_CORE_BINARY="${JOULAR_CACHE_DIR}/joularcore-linux-${JOULAR_ARCH}"

if [ -x "${JOULAR_CORE_BINARY}" ]; then
    ok "Joular Core found in cache: ${JOULAR_CORE_BINARY}"
else
    mkdir -p "${JOULAR_CACHE_DIR}"
    ASSET_NAME="joularcore-linux-${JOULAR_ARCH}"
    DOWNLOAD_URL="https://github.com/joular/joularcore/releases/download/${JOULAR_CORE_VERSION}/${ASSET_NAME}"
    info "Downloading Joular Core from ${DOWNLOAD_URL} ..."
    DOWNLOADED=false
    if curl -fsSL -o "${JOULAR_CORE_BINARY}" "${DOWNLOAD_URL}"; then
        chmod +x "${JOULAR_CORE_BINARY}"
        ok "Joular Core downloaded and cached."
        DOWNLOADED=true
    else
        info "Download failed - will try building from source."
        rm -f "${JOULAR_CORE_BINARY}"
    fi

    if [ "${DOWNLOADED}" = false ]; then
        if ! command -v cargo >/dev/null 2>&1; then
            info "cargo not found - installing Rust toolchain via rustup..."
            curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
            # shellcheck source=/dev/null
            source "${HOME}/.cargo/env"
            ok "Rust toolchain installed: $(cargo --version)"
        fi

        JOULAR_SRC="${WORK_DIR}/joularcore-src"
        rm -rf "${JOULAR_SRC}"
        git clone --depth 1 --branch "${JOULAR_CORE_VERSION}" \
            https://github.com/joular/joularcore.git "${JOULAR_SRC}"

        cd "${JOULAR_SRC}"
        cargo build --release

        cp target/release/joularcore "${JOULAR_CORE_BINARY}"
        chmod +x "${JOULAR_CORE_BINARY}"
        ok "Joular Core built and cached."
    fi
fi

# ── VM flags ──────────────────────────────────────────────────────────────────
VM_FLAGS=""
if [ "${POWER_SOURCE}" = "vm-file" ] || [ "${POWER_SOURCE}" = "ci-estimated" ]; then
    VM_FLAGS="-Dgreener.vmMode=true -Dgreener.vmPowerFilePath=${VM_POWER_FILE}"
fi

# ── Run each tool ─────────────────────────────────────────────────────────────
PASSED=""
FAILED=""
SKIPPED=""

for tool in ${TOOLS}; do
    BIN="$(tool_binary "${tool}")"

    if [ -z "${BIN}" ] || ! command -v "${BIN}" >/dev/null 2>&1; then
        SKIPPED="${SKIPPED} ${tool}"
        continue
    fi

    TOOL_SCRIPT="${PETCLINIC_DIR}/examples/workloads/${tool}/run.sh"
    if [ ! -f "${TOOL_SCRIPT}" ]; then
        SKIPPED="${SKIPPED} ${tool}"
        continue
    fi

    REPORTS_DIR="${WORK_DIR}/greener-reports-${tool}"

    banner "Measuring with ${tool}"

    cd "${PETCLINIC_DIR}"
    if mvn --batch-mode --no-transfer-progress \
        com.patbaumgartner:greener-spring-boot-maven-plugin:0.2.0-SNAPSHOT:measure \
        -Dgreener.joularCoreBinaryPath="${JOULAR_CORE_BINARY}" \
        -Dgreener.baseUrl="http://localhost:8080" \
        -Dgreener.externalTrainingScriptFile="${TOOL_SCRIPT}" \
        -Dgreener.warmupDurationSeconds="${WARMUP_SECONDS}" \
        -Dgreener.measureDurationSeconds="${MEASURE_SECONDS}" \
        -Dgreener.requestsPerSecond=20 \
        -Dgreener.healthCheckPath=/actuator/health/readiness \
        -Dgreener.failOnRegression=false \
        -Dgreener.reportOutputDir="${REPORTS_DIR}" \
        ${VM_FLAGS}; then
        PASSED="${PASSED} ${tool}"
        ok "${tool} measurement complete → ${REPORTS_DIR}/"
    else
        FAILED="${FAILED} ${tool}"
        warn "${tool} measurement failed"
    fi
done

# ── Summary ──────────────────────────────────────────────────────────────────
banner "All-Tools Summary"

echo ""
printf "  %-12s  %-10s  %s\n" "TOOL" "STATUS" "REPORT"
printf "  %-12s  %-10s  %s\n" "────────────" "──────────" "──────────────────────────────────"

for tool in ${TOOLS}; do
    REPORTS_DIR="${WORK_DIR}/greener-reports-${tool}"
    REPORT_JSON="${REPORTS_DIR}/latest-energy-report.json"
    if echo "${PASSED}" | grep -qw "${tool}"; then
        ENERGY="—"
        if [ -f "${REPORT_JSON}" ] && command -v python3 >/dev/null 2>&1; then
            ENERGY="$(python3 -c "import json; print(f'{json.load(open(\"${REPORT_JSON}\"))[\"report\"][\"totalEnergyJoules\"]:.2f} J')" 2>/dev/null || echo "—")"
        fi
        printf "  %-12s  ✅ passed   %s\n" "${tool}" "${ENERGY}"
    elif echo "${FAILED}" | grep -qw "${tool}"; then
        printf "  %-12s  ❌ failed   —\n" "${tool}"
    else
        printf "  %-12s  ⏭  skipped  (not installed)\n" "${tool}"
    fi
done

PASS_COUNT=0; FAIL_COUNT=0; SKIP_COUNT=0
for t in ${PASSED}; do PASS_COUNT=$((PASS_COUNT + 1)); done
for t in ${FAILED}; do FAIL_COUNT=$((FAIL_COUNT + 1)); done
for t in ${SKIPPED}; do SKIP_COUNT=$((SKIP_COUNT + 1)); done

echo ""
echo "  Passed: ${PASS_COUNT}  |  Failed: ${FAIL_COUNT}  |  Skipped: ${SKIP_COUNT}"
echo ""
echo "  Reports saved under: ${WORK_DIR}/greener-reports-*/"

if [ "${FAIL_COUNT}" -gt 0 ]; then
    exit 1
fi
