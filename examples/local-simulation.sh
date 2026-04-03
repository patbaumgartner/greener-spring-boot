#!/usr/bin/env bash
# local-simulation.sh
#
# Simulates the full energy-measurement workflow on your local machine:
#
#   1. Build greener-spring-boot plugins + Spring Petclinic
#   2. Run a baseline measurement  → save as baseline JSON
#   3. Run a second measurement    → compare against the baseline
#
# Both measurements happen on the same machine under identical conditions,
# so the comparison shows how reproducible the results are (expected delta ≈ 0 %).
#
# Power source auto-detection (three tiers):
#   1. RAPL          — bare-metal Linux with readable powercap
#   2. Scaphandre VM — KVM guest with host-side power file
#   3. CPU × TDP     — software estimation via /proc/stat
#
# Prerequisites:
#   - Java 25+ (temurin recommended)
#   - Maven
#   - git, curl, python3 — installed automatically if missing
#   - Rust toolchain (cargo) — installed automatically if missing
#   - oha (installed automatically if missing)
#
# Usage:
#   ./examples/local-simulation.sh
#
# Environment variables (all optional — sensible defaults are used):
#   JAVA_VERSION           Java version label for display  (default: 25)
#   PETCLINIC_VERSION      Branch/tag to clone             (default: main)
#   JOULAR_CORE_VERSION    Joular Core release tag         (default: 0.0.1-alpha-11)
#   MEASURE_SECONDS        Measurement duration            (default: 60)
#   WARMUP_SECONDS         Warmup duration                 (default: 30)
#   THRESHOLD              Regression threshold in %       (default: 10)
#   OHA_VERSION            oha release version             (default: 1.4.5)
#   TDP_WATTS              TDP for CPU estimation          (default: 100)
#   VM_POWER_FILE          Scaphandre VM power file path   (default: unset)
#   WORK_DIR               Temporary working directory     (default: /tmp/greener-local-sim)

set -euo pipefail

# ── Configuration ─────────────────────────────────────────────────────────────
PETCLINIC_VERSION="${PETCLINIC_VERSION:-main}"
JOULAR_CORE_VERSION="${JOULAR_CORE_VERSION:-0.0.1-alpha-11}"
MEASURE_SECONDS="${MEASURE_SECONDS:-60}"
WARMUP_SECONDS="${WARMUP_SECONDS:-30}"
THRESHOLD="${THRESHOLD:-10}"
OHA_VERSION="${OHA_VERSION:-1.4.5}"
TDP_WATTS="${TDP_WATTS:-100}"
WORK_DIR="${WORK_DIR:-/tmp/greener-local-sim}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
COMMIT_SHA="$(git -C "${PROJECT_ROOT}" rev-parse HEAD 2>/dev/null || echo "unknown")"
BRANCH="$(git -C "${PROJECT_ROOT}" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")"

PETCLINIC_DIR="${WORK_DIR}/spring-petclinic"
BASELINE_FILE="${WORK_DIR}/energy-baseline.json"
REPORTS_BASELINE="${WORK_DIR}/greener-reports-baseline"
REPORTS_COMPARISON="${WORK_DIR}/greener-reports-comparison"
CI_POWER_FILE="${WORK_DIR}/ci-power.txt"
JOULAR_CACHE_DIR="${HOME}/.greener/cache/joularcore"

CI_ESTIMATOR_PID=""

# ── Helpers ───────────────────────────────────────────────────────────────────
info()  { echo "ℹ️  $*"; }
ok()    { echo "✅ $*"; }
warn()  { echo "⚠️  $*"; }
banner() {
    echo ""
    echo "════════════════════════════════════════════════════════════"
    echo "  $*"
    echo "════════════════════════════════════════════════════════════"
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

command -v java  >/dev/null 2>&1 || { echo "❌ java not found. Install JDK 25+."; exit 1; }
command -v mvn   >/dev/null 2>&1 || { echo "❌ mvn not found. Install Maven."; exit 1; }

# Auto-install lightweight tools if missing
for tool in git curl python3; do
    if ! command -v "${tool}" >/dev/null 2>&1; then
        info "${tool} not found — installing..."
        if command -v apt-get >/dev/null 2>&1; then
            sudo apt-get update -qq && sudo apt-get install -y -qq "${tool}"
        elif command -v dnf >/dev/null 2>&1; then
            sudo dnf install -y -q "${tool}"
        elif command -v yum >/dev/null 2>&1; then
            sudo yum install -y -q "${tool}"
        elif command -v brew >/dev/null 2>&1; then
            brew install "${tool}"
        else
            echo "❌ ${tool} not found and no supported package manager detected."; exit 1
        fi
        ok "${tool} installed"
    fi
done

java -version 2>&1 | head -1
mvn --version 2>&1 | head -1

mkdir -p "${WORK_DIR}"

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

# ── Install oha ──────────────────────────────────────────────────────────────
banner "Checking oha ${OHA_VERSION}"

if command -v oha >/dev/null 2>&1; then
    ok "oha already installed: $(oha --version)"
else
    info "Installing oha ${OHA_VERSION}..."
    ARCH="$(uname -m)"
    case "${ARCH}" in
        x86_64)  OHA_ARCH="amd64" ;;
        aarch64) OHA_ARCH="arm64" ;;
        *)       echo "❌ Unsupported architecture: ${ARCH}"; exit 1 ;;
    esac
    OHA_URL="https://github.com/hatoo/oha/releases/download/v${OHA_VERSION}/oha-linux-${OHA_ARCH}"
    curl -fsSL -o "${WORK_DIR}/oha" "${OHA_URL}"
    chmod +x "${WORK_DIR}/oha"
    export PATH="${WORK_DIR}:${PATH}"
    ok "oha installed: $(oha --version)"
fi

# ── Copy workload scripts ────────────────────────────────────────────────────
cp -r "${PROJECT_ROOT}/examples" "${PETCLINIC_DIR}/"

# ── Power source detection ───────────────────────────────────────────────────
banner "Detecting power source"

POWER_SOURCE="none"

if [ -r /sys/class/powercap/intel-rapl/intel-rapl:0/energy_uj ]; then
    POWER_SOURCE="rapl"
    ok "RAPL readable — hardware energy measurement."
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

JOULAR_CORE_BINARY="${JOULAR_CACHE_DIR}/joularcore-linux-x86_64"

if [ -x "${JOULAR_CORE_BINARY}" ]; then
    ok "Joular Core found in cache: ${JOULAR_CORE_BINARY}"
else
    info "Building Joular Core from source..."
    if ! command -v cargo >/dev/null 2>&1; then
        info "cargo not found — installing Rust toolchain via rustup..."
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

    mkdir -p "${JOULAR_CACHE_DIR}"
    cp target/release/joularcore "${JOULAR_CORE_BINARY}"
    chmod +x "${JOULAR_CORE_BINARY}"
    ok "Joular Core built and cached."
fi

# ── VM flags ──────────────────────────────────────────────────────────────────
VM_FLAGS=""
if [ "${POWER_SOURCE}" = "vm-file" ] || [ "${POWER_SOURCE}" = "ci-estimated" ]; then
    VM_FLAGS="-Dgreener.vmMode=true -Dgreener.vmPowerFilePath=${VM_POWER_FILE}"
fi

# ── Run 1: Baseline measurement ──────────────────────────────────────────────
banner "RUN 1 — BASELINE"

cd "${PETCLINIC_DIR}"
JAR="$(find target -name "*.jar" ! -name "*-sources.jar" | head -1)"

mvn --batch-mode --no-transfer-progress \
    com.patbaumgartner:greener-spring-boot-maven-plugin:0.1.0-SNAPSHOT:measure \
    -Dgreener.springBootJar="${JAR}" \
    -Dgreener.joularCoreBinaryPath="${JOULAR_CORE_BINARY}" \
    -Dgreener.baseUrl="http://localhost:8080" \
    -Dgreener.externalTrainingScriptFile="${PETCLINIC_DIR}/examples/workloads/oha/run.sh" \
    -Dgreener.warmupDurationSeconds="${WARMUP_SECONDS}" \
    -Dgreener.measureDurationSeconds="${MEASURE_SECONDS}" \
    -Dgreener.requestsPerSecond=20 \
    -Dgreener.healthCheckPath=/actuator/health/readiness \
    -Dgreener.baselineFile="${BASELINE_FILE}" \
    -Dgreener.failOnRegression=false \
    -Dgreener.reportOutputDir="${REPORTS_BASELINE}" \
    ${VM_FLAGS}

# ── Promote Run 1 to baseline ────────────────────────────────────────────────
banner "Promoting Run 1 to baseline"

mvn --batch-mode --no-transfer-progress \
    com.patbaumgartner:greener-spring-boot-maven-plugin:0.1.0-SNAPSHOT:update-baseline \
    -Dgreener.baselineFile="${BASELINE_FILE}" \
    -Dgreener.latestReportFile="${REPORTS_BASELINE}/latest-energy-report.json" \
    -Dgreener.commitSha="${COMMIT_SHA}" \
    -Dgreener.branch="${BRANCH}"

echo ""
echo "📊 Baseline created:"
python3 -c "
import json
b = json.load(open('${BASELINE_FILE}'))
e = b['report']['totalEnergyJoules']
print(f'  Energy : {e:.4f} J')
print(f'  Commit : ${COMMIT_SHA}')
"

# ── Run 2: Comparison measurement ────────────────────────────────────────────
banner "RUN 2 — COMPARISON (vs baseline from Run 1)"

mvn --batch-mode --no-transfer-progress \
    com.patbaumgartner:greener-spring-boot-maven-plugin:0.1.0-SNAPSHOT:measure \
    -Dgreener.springBootJar="${JAR}" \
    -Dgreener.joularCoreBinaryPath="${JOULAR_CORE_BINARY}" \
    -Dgreener.baseUrl="http://localhost:8080" \
    -Dgreener.externalTrainingScriptFile="${PETCLINIC_DIR}/examples/workloads/oha/run.sh" \
    -Dgreener.warmupDurationSeconds="${WARMUP_SECONDS}" \
    -Dgreener.measureDurationSeconds="${MEASURE_SECONDS}" \
    -Dgreener.requestsPerSecond=20 \
    -Dgreener.healthCheckPath=/actuator/health/readiness \
    -Dgreener.baselineFile="${BASELINE_FILE}" \
    -Dgreener.threshold="${THRESHOLD}" \
    -Dgreener.failOnRegression=false \
    -Dgreener.reportOutputDir="${REPORTS_COMPARISON}" \
    ${VM_FLAGS}

# ── Summary ──────────────────────────────────────────────────────────────────
banner "RESULTS — Baseline vs Comparison"

python3 - <<PYEOF
import json, os

baseline = json.load(open("${BASELINE_FILE}"))
b_energy = baseline["report"]["totalEnergyJoules"]

comparison = json.load(open("${REPORTS_COMPARISON}/latest-energy-report.json"))
c_energy = comparison["report"]["totalEnergyJoules"]

delta = c_energy - b_energy
pct = (delta / b_energy * 100) if b_energy > 0 else 0

print(f"  Baseline energy  : {b_energy:.4f} J")
print(f"  Comparison energy: {c_energy:.4f} J")
print(f"  Delta            : {delta:+.4f} J ({pct:+.2f} %)")
print(f"  Threshold        : ±${THRESHOLD} %")
print()

if abs(pct) <= float("${THRESHOLD}"):
    print("  ✅ Within threshold — results are reproducible.")
else:
    print("  ⚠️  Outside threshold — noisy environment or real regression.")
PYEOF

echo ""
echo "Reports saved to:"
echo "  Baseline  : ${REPORTS_BASELINE}/"
echo "  Comparison: ${REPORTS_COMPARISON}/"
echo "  Baseline JSON: ${BASELINE_FILE}"
