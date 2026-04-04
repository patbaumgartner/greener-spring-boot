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
# Baseline behaviour:
#   - Each tool gets its own baseline file under WORK_DIR/baselines/
#     (e.g. baselines/oha-energy-baseline.json).
#   - On the first run (or after RESET_BASELINES=true) there is no
#     baseline, so each tool reports "No baseline".
#   - After each successful measurement the result is promoted to a
#     baseline, so subsequent runs compare against the previous result.
#
# Report history:
#   - Each script invocation creates a timestamped report directory
#     (e.g. greener-reports-20260404-153012/) so previous runs are kept.
#   - A "latest" symlink always points to the most recent run.
#
# Supported tools (auto-installed by each workload script if not present):
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
#   RESET_BASELINES        Delete stored baselines first   (default: unset)

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
BASELINE_DIR="${WORK_DIR}/baselines"
RUN_TIMESTAMP="$(date +%Y%m%d-%H%M%S)"

CI_ESTIMATOR_PID=""

# ── Tool list ─────────────────────────────────────────────────────────────────
TOOLS="oha wrk wrk2 bombardier ab k6 locust gatling"

# ── Helpers ───────────────────────────────────────────────────────────────────
info()  { echo "[ii] $*"; }
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

ok "All preflight checks passed"

# ── Baseline management ──────────────────────────────────────────────────────
if [ "${RESET_BASELINES:-}" = "true" ] && [ -d "${BASELINE_DIR}" ]; then
    info "RESET_BASELINES=true — removing stored baselines"
    rm -rf "${BASELINE_DIR}"
fi
mkdir -p "${BASELINE_DIR}"

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
# Each invocation gets a timestamped report directory so previous runs are
# preserved.  A "latest" symlink always points to the most recent run.
# Per-tool baselines are stored in BASELINE_DIR and persist across runs.
REPORTS_DIR="${WORK_DIR}/greener-reports-${RUN_TIMESTAMP}"
PASSED=""
FAILED=""
SKIPPED=""

COMMIT_SHA="$(git -C "${PROJECT_ROOT}" rev-parse HEAD 2>/dev/null || echo "unknown")"
BRANCH="$(git -C "${PROJECT_ROOT}" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")"

for tool in ${TOOLS}; do
    TOOL_SCRIPT="${PETCLINIC_DIR}/examples/workloads/${tool}/run.sh"
    if [ ! -f "${TOOL_SCRIPT}" ]; then
        SKIPPED="${SKIPPED} ${tool}"
        continue
    fi

    TOOL_BASELINE="${BASELINE_DIR}/${tool}-energy-baseline.json"

    banner "Measuring with ${tool}"
    if [ -f "${TOOL_BASELINE}" ]; then
        info "Baseline found for ${tool} — comparison will be performed"
    else
        info "No baseline for ${tool} — first run, no comparison"
    fi

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
        -Dgreener.baselineFile="${TOOL_BASELINE}" \
        -Dgreener.threshold="${THRESHOLD}" \
        -Dgreener.failOnRegression=false \
        -Dgreener.reportOutputDir="${REPORTS_DIR}" \
        ${VM_FLAGS}; then
        PASSED="${PASSED} ${tool}"
        ok "${tool} measurement complete → ${REPORTS_DIR}/${tool}/"

        # Promote this result as the new baseline for the next run
        mvn --batch-mode --no-transfer-progress \
            com.patbaumgartner:greener-spring-boot-maven-plugin:0.2.0-SNAPSHOT:update-baseline \
            -Dgreener.baselineFile="${TOOL_BASELINE}" \
            -Dgreener.latestReportFile="${REPORTS_DIR}/${tool}/latest-energy-report.json" \
            -Dgreener.commitSha="${COMMIT_SHA}" \
            -Dgreener.branch="${BRANCH}" || warn "Failed to update baseline for ${tool}"
    else
        FAILED="${FAILED} ${tool}"
        warn "${tool} measurement failed"
    fi
done

# ── Symlink latest reports ────────────────────────────────────────────────────
LATEST_LINK="${WORK_DIR}/greener-reports-latest"
rm -f "${LATEST_LINK}"
ln -s "${REPORTS_DIR}" "${LATEST_LINK}"
info "Latest reports linked: ${LATEST_LINK} → ${REPORTS_DIR}"

# ── Summary ──────────────────────────────────────────────────────────────────
banner "All-Tools Summary"

echo ""
printf "  %-12s  %-10s  %s\n" "TOOL" "STATUS" "ENERGY"
printf "  %-12s  %-10s  %s\n" "────────────" "──────────" "──────────────────────────────────"

for tool in ${TOOLS}; do
    REPORT_JSON="${REPORTS_DIR}/${tool}/latest-energy-report.json"
    if echo "${PASSED}" | grep -qw "${tool}"; then
        ENERGY="—"
        if [ -f "${REPORT_JSON}" ] && command -v python3 >/dev/null 2>&1; then
            ENERGY="$(python3 -c "import json; print(f'{json.load(open(\"${REPORT_JSON}\"))[\"report\"][\"totalEnergyJoules\"]:.2f} J')" 2>/dev/null || echo "—")"
        fi
        printf "  %-12s  ✅ passed   %s\n" "${tool}" "${ENERGY}"
    elif echo "${FAILED}" | grep -qw "${tool}"; then
        printf "  %-12s  ❌ failed   —\n" "${tool}"
    else
        printf "  %-12s  ⏭  skipped  (run.sh not found)\n" "${tool}"
    fi
done

PASS_COUNT=0; FAIL_COUNT=0; SKIP_COUNT=0
for t in ${PASSED}; do PASS_COUNT=$((PASS_COUNT + 1)); done
for t in ${FAILED}; do FAIL_COUNT=$((FAIL_COUNT + 1)); done
for t in ${SKIPPED}; do SKIP_COUNT=$((SKIP_COUNT + 1)); done

echo ""
echo "  Passed: ${PASS_COUNT}  |  Failed: ${FAIL_COUNT}  |  Skipped: ${SKIP_COUNT}"
echo ""
echo "  Reports saved under: ${REPORTS_DIR}/"
echo "  Baselines saved under: ${BASELINE_DIR}/"
echo "  Latest symlink: ${LATEST_LINK}"

AGGREGATED_REPORT="${REPORTS_DIR}/greener-aggregated-report.html"
if [ -f "${AGGREGATED_REPORT}" ]; then
    echo "  Aggregated report: ${AGGREGATED_REPORT}"
fi

if [ "${FAIL_COUNT}" -gt 0 ]; then
    exit 1
fi
