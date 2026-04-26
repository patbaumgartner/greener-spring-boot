#!/usr/bin/env bash
# joularjx-simulation.sh
#
# Demonstrates how to use JoularJX (method-level energy monitoring) together
# with the greener-spring-boot Maven plugin and Joular Core (process-level).
#
# JoularJX is an optional Java agent that provides **per-method** energy
# granularity on top of Joular Core.  When attached to the Spring Boot JVM,
# it writes CSV files to joularjx-result/ while Joular Core still provides
# the process-level energy data used by the greener plugin reports.
#
# What this script does:
#   1. Build greener-spring-boot plugins + Spring Petclinic.
#   2. Download JoularJX agent JAR (if not cached).
#   3. Generate a config.properties for JoularJX.
#   4. Run the greener:measure goal with JoularJX attached as a -javaagent.
#   5. Display JoularJX per-method energy results alongside the greener report.
#
# The greener plugin still produces its own process-level HTML/console report.
# JoularJX adds method-level CSV files under joularjx-result/ so you can
# identify which Spring Boot methods consume the most energy.
#
# Prerequisites:
#   - Java 17+
#   - Maven
#   - git, curl, python3 — installed automatically if missing
#   - oha (installed automatically by the workload script if missing)
#
# Usage:
#   ./examples/joularjx-simulation.sh
#
# Environment variables (all optional — sensible defaults are used):
#   PETCLINIC_VERSION      Branch/tag to clone             (default: main)
#   JOULAR_CORE_VERSION    Joular Core release tag         (default: 0.0.1-beta-2)
#   JOULARJX_VERSION       JoularJX release tag            (default: 3.1.0)
#   MEASURE_SECONDS        Measurement duration            (default: 60)
#   WARMUP_SECONDS         Warmup duration                 (default: 30)
#   THRESHOLD              Regression threshold in %       (default: 10)
#   TDP_WATTS              TDP for CPU estimation          (default: 100)
#   VM_POWER_FILE          Scaphandre VM power file path   (default: unset)
#   WORK_DIR               Temporary working directory     (default: /tmp/greener-joularjx-sim)
#   FILTER_METHODS         JoularJX method filter          (default: org.springframework.samples.petclinic)
#   APP_PORT               HTTP port for Spring Boot        (default: random free port)

set -euo pipefail

# ── Configuration ─────────────────────────────────────────────────────────────
PETCLINIC_VERSION="${PETCLINIC_VERSION:-main}"
JOULAR_CORE_VERSION="${JOULAR_CORE_VERSION:-0.0.1-beta-2}"
JOULARJX_VERSION="${JOULARJX_VERSION:-3.1.0}"
MEASURE_SECONDS="${MEASURE_SECONDS:-60}"
WARMUP_SECONDS="${WARMUP_SECONDS:-30}"
THRESHOLD="${THRESHOLD:-10}"
TDP_WATTS="${TDP_WATTS:-100}"
WORK_DIR="${WORK_DIR:-/tmp/greener-joularjx-sim}"
FILTER_METHODS="${FILTER_METHODS:-org.springframework.samples.petclinic}"
APP_PORT="${APP_PORT:-$(python3 -c "import socket; s=socket.socket(); s.bind(('',0)); print(s.getsockname()[1]); s.close()")}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
COMMIT_SHA="$(git -C "${PROJECT_ROOT}" rev-parse HEAD 2>/dev/null || echo "unknown")"
BRANCH="$(git -C "${PROJECT_ROOT}" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")"

PETCLINIC_DIR="${WORK_DIR}/spring-petclinic"
BASELINE_FILE="${WORK_DIR}/energy-baseline.json"
RUN_TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
REPORTS_DIR="${WORK_DIR}/greener-reports-joularjx-${RUN_TIMESTAMP}"
CI_POWER_FILE="${WORK_DIR}/ci-power.txt"
JOULAR_CACHE_DIR="${HOME}/.greener/cache/joularcore"
JOULARJX_CACHE_DIR="${HOME}/.greener/cache/joularjx"

CI_ESTIMATOR_PID=""

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

case "$(uname -s 2>/dev/null || echo unknown)" in
    MINGW*|MSYS*|CYGWIN*)
        echo "[ERR] This script targets Linux / WSL2 / macOS."
        echo "      You appear to be running native Windows ($(uname -s))."
        echo "      Please run the PowerShell companion instead:"
        echo ""
        echo "          powershell -ExecutionPolicy Bypass -File examples/joularjx-simulation.ps1"
        echo ""
        echo "      Or re-run this script from inside WSL2."
        exit 1
        ;;
esac

command -v java  >/dev/null 2>&1 || { echo "[ERR] java not found. Install JDK 17+."; exit 1; }
command -v mvn   >/dev/null 2>&1 || { echo "[ERR] mvn not found. Install Maven."; exit 1; }

# Auto-install lightweight tools if missing
for tool in git curl python3; do
    if ! command -v "${tool}" >/dev/null 2>&1; then
        info "${tool} not found - installing..."
        if command -v apt-get >/dev/null 2>&1; then
            sudo apt-get update -qq && sudo apt-get install -y -qq "${tool}"
        elif command -v dnf >/dev/null 2>&1; then
            sudo dnf install -y -q "${tool}"
        elif command -v yum >/dev/null 2>&1; then
            sudo yum install -y -q "${tool}"
        elif command -v brew >/dev/null 2>&1; then
            brew install "${tool}"
        else
            echo "[ERR] ${tool} not found and no supported package manager detected."; exit 1
        fi
        ok "${tool} installed"
    fi
done

java -version 2>&1 | head -1
mvn --version 2>&1 | head -1

mkdir -p "${WORK_DIR}"

ok "All preflight checks passed"

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
    ok "RAPL readable - hardware energy measurement."
elif [ "$IS_WSL" = true ] && sc.exe query ScaphandreDrv 2>/dev/null | grep -q RUNNING 2>/dev/null; then
    POWER_SOURCE="ci-estimated"
    ok "WSL: Hubblo RAPL driver (ScaphandreDrv) detected on Windows host."
elif [ -n "${VM_POWER_FILE:-}" ] && [ -f "${VM_POWER_FILE}" ]; then
    POWER_SOURCE="vm-file"
    ok "VM power file found at ${VM_POWER_FILE}."
elif [ -f /proc/stat ]; then
    POWER_SOURCE="ci-estimated"
    info "Using CPU-time x TDP software estimation."
else
    warn "No power source - measurement will be skipped."
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
    info "Estimator running - current estimate: $(cat "${CI_POWER_FILE}") W"
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
    if curl -fsSL -o "${JOULAR_CORE_BINARY}" "${DOWNLOAD_URL}"; then
        chmod +x "${JOULAR_CORE_BINARY}"
        ok "Joular Core downloaded and cached."
    else
        info "Download failed - will try building from source."
        rm -f "${JOULAR_CORE_BINARY}"

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

# ── JoularJX agent ───────────────────────────────────────────────────────────
banner "Preparing JoularJX ${JOULARJX_VERSION}"

JOULARJX_JAR="${JOULARJX_CACHE_DIR}/joularjx-${JOULARJX_VERSION}.jar"

if [ -f "${JOULARJX_JAR}" ]; then
    ok "JoularJX agent found in cache: ${JOULARJX_JAR}"
else
    mkdir -p "${JOULARJX_CACHE_DIR}"
    JOULARJX_URL="https://github.com/joular/joularjx/releases/download/${JOULARJX_VERSION}/joularjx-${JOULARJX_VERSION}.jar"
    info "Downloading JoularJX from ${JOULARJX_URL} ..."
    if curl -fsSL -o "${JOULARJX_JAR}" "${JOULARJX_URL}"; then
        ok "JoularJX agent downloaded and cached."
    else
        echo "[ERR] Failed to download JoularJX ${JOULARJX_VERSION}."
        echo "      Download it manually from: https://github.com/joular/joularjx/releases"
        echo "      Place it at: ${JOULARJX_JAR}"
        exit 1
    fi
fi

# ── Generate JoularJX config.properties ──────────────────────────────────────
# Note: joular-core-parameters is intentionally omitted here.
# The greener Maven/Gradle plugin auto-detects the best power component
# (cpu or gpu) at startup via JoularCoreProbe.ensureJoularCoreParameters().
banner "Generating JoularJX config.properties"

JOULARJX_CONFIG="${WORK_DIR}/joularjx-config.properties"

cat > "${JOULARJX_CONFIG}" <<EOF
# JoularJX configuration for greener-spring-boot simulation
# Generated by joularjx-simulation.sh on $(date -Iseconds)

# Filter methods to monitor — only methods starting with this prefix
# will appear in the filtered 'app/' results.  Unfiltered results are
# always available under 'all/'.
filter-method-names=${FILTER_METHODS}

# Spring Boot runs as an application server
application-server=true

# Use Joular Core for power readings (same binary the greener plugin uses)
joular-core=true
joular-core-path=${JOULAR_CORE_BINARY}
joular-core-ring-buffer=false

# Save per-second runtime method power data
save-runtime-data=true
overwrite-runtime-data=false

# Track method power evolution over time
track-consumption-evolution=true

# Enable call-tree energy tracking
enable-call-trees-consumption=true
save-call-trees-runtime-data=false

# Hide JoularJX agent threads from results
hide-agent-consumption=true

# Logging
logger-level=INFO
EOF

ok "Config written to: ${JOULARJX_CONFIG}"
grep -v "^#" "${JOULARJX_CONFIG}" | grep -v "^$" | sed 's/^/  /'

# ── VM flags ──────────────────────────────────────────────────────────────────
VM_FLAGS=""
if [ "${POWER_SOURCE}" = "vm-file" ] || [ "${POWER_SOURCE}" = "ci-estimated" ]; then
    VM_FLAGS="-Dgreener.vmMode=true -Dgreener.vmPowerFilePath=${VM_POWER_FILE}"
fi

# ── Run measurement with JoularJX ────────────────────────────────────────────
banner "MEASUREMENT with JoularJX agent"

info "JoularJX agent : ${JOULARJX_JAR}"
info "JoularJX config: ${JOULARJX_CONFIG}"
info "Filter methods : ${FILTER_METHODS}"

cd "${PETCLINIC_DIR}"

# The JoularJX agent is attached via the greener plugin's joularJxAgentPath
# and joularJxConfigPath parameters. The plugin passes -javaagent: and
# -Djoularjx.config= to the Spring Boot JVM, which causes JoularJX to run
# inside the same JVM and write its own per-method CSV results to
# joularjx-result/.

mvn --batch-mode --no-transfer-progress \
    com.patbaumgartner:greener-spring-boot-maven-plugin:0.2.0-SNAPSHOT:measure \
    -Dgreener.joularCoreBinaryPath="${JOULAR_CORE_BINARY}" \
    -Dgreener.baseUrl="http://localhost:${APP_PORT}" \
    -Dgreener.externalTrainingScriptFile="${PETCLINIC_DIR}/examples/workloads/oha/run.sh" \
    -Dgreener.warmupDurationSeconds="${WARMUP_SECONDS}" \
    -Dgreener.measureDurationSeconds="${MEASURE_SECONDS}" \
    -Dgreener.requestsPerSecond=20 \
    -Dgreener.healthCheckPath=/actuator/health/readiness \
    -Dgreener.baselineFile="${BASELINE_FILE}" \
    -Dgreener.threshold="${THRESHOLD}" \
    -Dgreener.failOnRegression=false \
    -Dgreener.reportOutputDir="${REPORTS_DIR}" \
    -Dgreener.autoUpdateBaseline=true \
    -Dgreener.commitSha="${COMMIT_SHA}" \
    -Dgreener.branch="${BRANCH}" \
    -Dgreener.joularJxAgentPath="${JOULARJX_JAR}" \
    -Dgreener.joularJxConfigPath="${JOULARJX_CONFIG}" \
    ${VM_FLAGS}

# ── Display greener plugin results ───────────────────────────────────────────
banner "GREENER PLUGIN RESULTS (process-level)"

if [ -f "${BASELINE_FILE}" ]; then
    python3 -c "
import json
b = json.load(open('${BASELINE_FILE}'))
e = b['report']['totalEnergyJoules']
d = b['report']['durationSeconds']
print(f'  Total energy : {e:.2f} J')
print(f'  Duration     : {d} s')
print(f'  Avg power    : {e/d:.2f} W')
print(f'  Commit       : ${COMMIT_SHA}')
"
else
    warn "Baseline file not found — check Maven output above for errors."
fi

echo ""

# ── Display JoularJX results ─────────────────────────────────────────────────
banner "JOULARJX RESULTS (method-level)"

# JoularJX writes results relative to the JVM working directory (plugin work dir)
JOULARJX_RESULTS="${REPORTS_DIR}/oha/work/joularjx-result"

if [ -d "${JOULARJX_RESULTS}" ]; then
    # Find the latest run directory
    LATEST_RUN="$(find "${JOULARJX_RESULTS}/" -maxdepth 1 -type d ! -path "${JOULARJX_RESULTS}/" -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -1 | cut -d' ' -f2-)"

    if [ -n "${LATEST_RUN}" ]; then
        ok "JoularJX results found: ${LATEST_RUN}"

        # Show filtered app results (methods matching FILTER_METHODS)
        APP_METHODS="${LATEST_RUN}/app/total/methods"
        if [ -d "${APP_METHODS}" ]; then
            echo ""
            echo "  Filtered methods (${FILTER_METHODS}):"
            echo "  ──────────────────────────────────────────"
            for csv in "${APP_METHODS}"/*.csv; do
                [ -f "${csv}" ] || continue
                echo ""
                echo "  File: $(basename "${csv}")"
                # Sort by energy descending, show top 15 methods
                sort -t',' -k2 -rn "${csv}" | head -15 | while IFS=',' read -r method energy; do
                    printf "    %-70s %10s J\n" "${method}" "${energy}"
                done || true
            done
        fi

        # Show all methods total
        ALL_METHODS="${LATEST_RUN}/all/total/methods"
        if [ -d "${ALL_METHODS}" ]; then
            echo ""
            echo "  All methods (unfiltered, top 20):"
            echo "  ──────────────────────────────────────────"
            for csv in "${ALL_METHODS}"/*.csv; do
                [ -f "${csv}" ] || continue
                sort -t',' -k2 -rn "${csv}" | head -20 | while IFS=',' read -r method energy; do
                    printf "    %-70s %10s J\n" "${method}" "${energy}"
                done || true
            done
        fi

        # Copy JoularJX results alongside greener reports
        JOULARJX_REPORT_COPY="${REPORTS_DIR}/joularjx-result"
        cp -r "${LATEST_RUN}" "${JOULARJX_REPORT_COPY}"
        ok "JoularJX results copied to: ${JOULARJX_REPORT_COPY}"
    else
        warn "No JoularJX run directory found."
    fi
else
    warn "JoularJX results directory not found at: ${JOULARJX_RESULTS}"
    warn "JoularJX may not have started correctly. Check app-stderr.log for agent errors."
fi

# ── Symlink latest reports ────────────────────────────────────────────────────
LATEST_REPORTS="${WORK_DIR}/greener-reports-joularjx-latest"
rm -f "${LATEST_REPORTS}"
ln -s "${REPORTS_DIR}" "${LATEST_REPORTS}"
info "Latest reports linked: ${LATEST_REPORTS}"

# ── Summary ──────────────────────────────────────────────────────────────────
banner "SUMMARY"

echo "  This simulation demonstrated JoularJX method-level energy monitoring"
echo "  running alongside the greener-spring-boot Maven plugin."
echo ""
echo "  The greener report shows total process energy consumption."
echo "  The JoularJX CSVs show which individual methods consumed the most energy."
echo "  Together, they provide both a high-level overview and method-level detail."
echo ""
echo "Reports saved to:"
echo "  Greener report : ${REPORTS_DIR}/"
echo "  JoularJX CSVs  : ${REPORTS_DIR}/joularjx-result/"
echo "  Baseline JSON  : ${BASELINE_FILE}"
echo ""
echo "Open in browser:"
echo "  file://${REPORTS_DIR}/oha/greener-energy-report.html"
