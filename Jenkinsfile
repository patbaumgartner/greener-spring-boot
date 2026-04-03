// Jenkinsfile — Declarative pipeline for greener-spring-boot
//
// Prerequisites (configure in Manage Jenkins → Tools):
//   • JDK installation named "temurin-25"
//   • Maven installation named "maven-3"
//
// Power source auto-detection (same logic as the GitHub Actions / GitLab pipelines):
//   1. RAPL readable          → hardware measurement
//   2. $VM_POWER_FILE set     → Scaphandre VM measurement
//   3. /proc/stat readable    → CPU-time × TDP estimation  ← default
//
// Set the TDP_WATTS environment variable in the Jenkins job / pipeline parameters
// to match your agent hardware (default: 100 W).

pipeline {
    agent { label 'linux' }   // use 'any' for single-agent setups

    tools {
        jdk   'temurin-25'
        maven 'maven-3'
    }

    environment {
        PETCLINIC_VERSION   = 'main'
        JOULAR_CORE_VERSION = '0.0.1-alpha-11'
        OHA_VERSION         = '1.4.5'
        WARMUP_SECONDS      = '30'
        MEASURE_SECONDS     = '60'
        TDP_WATTS           = '100'
        MAVEN_OPTS          = "-Dmaven.repo.local=${WORKSPACE}/.m2/repository"
        MAVEN_CLI_OPTS      = '--batch-mode --no-transfer-progress'
    }

    options {
        timestamps()
        timeout(time: 90, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '30'))
    }

    stages {

        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build') {
            steps {
                sh 'mvn ${MAVEN_CLI_OPTS} clean install -DskipTests'
                dir('greener-spring-boot-gradle-plugin') {
                    sh './gradlew build --no-daemon -x test'
                }
            }
            post {
                success {
                    archiveArtifacts artifacts: '*/target/*.jar', allowEmptyArchive: true
                }
            }
        }

        stage('Test') {
            steps {
                sh 'mvn ${MAVEN_CLI_OPTS} verify'
            }
            post {
                always {
                    junit '**/target/surefire-reports/TEST-*.xml'
                }
            }
        }

        stage('Prepare Petclinic') {
            steps {
                sh '''
                    git clone --depth 1 --branch ${PETCLINIC_VERSION} \
                        https://github.com/spring-projects/spring-petclinic.git \
                        /tmp/spring-petclinic
                    cd /tmp/spring-petclinic
                    mvn ${MAVEN_CLI_OPTS} package -DskipTests
                '''
                sh '''
                    curl -fsSL -o /usr/local/bin/oha \
                        "https://github.com/hatoo/oha/releases/download/v${OHA_VERSION}/oha-linux-amd64"
                    chmod +x /usr/local/bin/oha
                    cp -r ${WORKSPACE}/examples /tmp/spring-petclinic/
                '''
            }
        }

        stage('Energy Measurement') {
            steps {
                sh '''#!/usr/bin/env bash
set -eu

# ── Detect power source ───────────────────────────────────────────────────
if [ -r /sys/class/powercap/intel-rapl/intel-rapl:0/energy_uj ]; then
    POWER_SOURCE=rapl
    echo "✅ RAPL readable — hardware measurement."
elif [ -n "${VM_POWER_FILE:-}" ] && [ -f "${VM_POWER_FILE}" ]; then
    POWER_SOURCE=vm-file
    echo "✅ Scaphandre VM power file: ${VM_POWER_FILE}"
elif [ -f /proc/stat ]; then
    POWER_SOURCE=ci-estimated
    VM_POWER_FILE=/tmp/ci-power.txt
    bash "${WORKSPACE}/examples/vm-setup/ci-cpu-energy-estimator.sh" \
        "${VM_POWER_FILE}" "${TDP_WATTS}" &
    CI_ESTIMATOR_PID=$!
    sleep 2
    echo "ℹ️  CI estimation: $(cat ${VM_POWER_FILE}) W (TDP=${TDP_WATTS} W)"
else
    POWER_SOURCE=none
    echo "⚠️  No power source detected — measurement skipped."
fi

if [ "${POWER_SOURCE}" = "none" ]; then
    exit 0
fi

# ── Download Joular Core ──────────────────────────────────────────────────
mkdir -p ~/.greener/cache/joularcore
BINARY=joularcore-linux-x86_64
curl -fsSL \
    -o ~/.greener/cache/joularcore/${BINARY} \
    "https://github.com/joular/joularcore/releases/download/v${JOULAR_CORE_VERSION}/${BINARY}"
chmod +x ~/.greener/cache/joularcore/${BINARY}
JOULAR_CORE_BINARY="${HOME}/.greener/cache/joularcore/${BINARY}"

# ── Measure ───────────────────────────────────────────────────────────────
JAR=$(find /tmp/spring-petclinic/target -name "*.jar" ! -name "*-sources.jar" | head -1)
VM_FLAGS=""
if [ "${POWER_SOURCE}" = "vm-file" ] || [ "${POWER_SOURCE}" = "ci-estimated" ]; then
    VM_FLAGS="-Dgreener.vmMode=true -Dgreener.vmPowerFilePath=${VM_POWER_FILE}"
fi

cd /tmp/spring-petclinic
mvn ${MAVEN_CLI_OPTS} \
    com.patbaumgartner:greener-spring-boot-maven-plugin:0.1.0-SNAPSHOT:measure \
    -Dgreener.springBootJar="${JAR}" \
    -Dgreener.joularCoreBinaryPath="${JOULAR_CORE_BINARY}" \
    -Dgreener.joularCoreComponent=cpu \
    -Dgreener.baseUrl="http://localhost:8080" \
    -Dgreener.externalTrainingScriptFile=/tmp/spring-petclinic/examples/workloads/oha/run.sh \
    -Dgreener.warmupDurationSeconds="${WARMUP_SECONDS}" \
    -Dgreener.measureDurationSeconds="${MEASURE_SECONDS}" \
    -Dgreener.requestsPerSecond=20 \
    -Dgreener.healthCheckPath=/actuator/health \
    -Dgreener.baselineFile="${WORKSPACE}/energy-baseline.json" \
    -Dgreener.failOnRegression=false \
    -Dgreener.reportOutputDir="${WORKSPACE}/greener-reports" \
    ${VM_FLAGS}

# ── Stop estimator ────────────────────────────────────────────────────────
if [ -n "${CI_ESTIMATOR_PID:-}" ]; then
    kill "${CI_ESTIMATOR_PID}" 2>/dev/null || true
fi
                '''
            }
            post {
                always {
                    archiveArtifacts artifacts: 'greener-reports/**',    allowEmptyArchive: true
                    archiveArtifacts artifacts: 'energy-baseline.json',  allowEmptyArchive: true
                }
            }
        }
    }

    post {
        always {
            cleanWs(cleanWhenNotBuilt: false,
                    deleteDirs:         true,
                    disableDeferredWipeout: true,
                    notFailBuild:       true,
                    patterns:           [[pattern: '.m2', type: 'EXCLUDE']])
        }
    }
}
