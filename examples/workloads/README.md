# Workload Examples for greener-spring-boot

This directory contains ready-to-use load-test scripts for popular HTTP benchmarking
tools.  Pass any of them to the greener-spring-boot plugin via
`externalTrainingScriptFile` to use them as the energy measurement workload.

 > **Tip**: configure an `externalTrainingScriptFile` to get reproducible load
> patterns.  Pick the tool that best matches your environment from the table below.

> **Auto-install**: each tool's `run.sh` script automatically installs the tool
> if it is not already present on the system.  No manual pre-installation is
> required - just run the script and it will handle the rest.

---

## Available tools

| Directory    | Tool | Best for |
|---|---|---|
| `oha/`       | [oha](https://github.com/hatoo/oha)        | CI pipelines - single static binary, constant-rate load |
| `k6/`        | [k6](https://grafana.com/docs/k6/)         | Scripted scenarios, thresholds, multi-scenario |
| `wrk/`       | [wrk](https://github.com/wg/wrk)           | High-throughput benchmarking, Lua scripting |
| `wrk2/`      | [wrk2](https://github.com/giltene/wrk2)    | Coordinated-omission-free constant-throughput |
| `gatling/`   | [Gatling](https://gatling.io)              | Scala DSL, rich HTML reports, assertions |
| `locust/`    | [Locust](https://locust.io)                | Python, distributed, flexible user model |
| `ab/`        | [Apache Bench](https://httpd.apache.org/docs/2.4/programs/ab.html) | Simplest option, pre-installed everywhere |
| `bombardier/`| [Bombardier](https://github.com/codesenberg/bombardier) | Lightweight Go binary, rate limiting |

---

## How to use

### Maven plugin

```xml
<configuration>
  <springBootJar>${project.build.directory}/myapp.jar</springBootJar>
  <!-- Always specify an external script - choose one of the examples below -->
  <externalTrainingScriptFile>${project.basedir}/examples/workloads/oha/run.sh</externalTrainingScriptFile>
  <warmupDurationSeconds>30</warmupDurationSeconds>
  <measureDurationSeconds>60</measureDurationSeconds>
  <requestsPerSecond>50</requestsPerSecond>
</configuration>
```

```bash
mvn greener:measure
```

### Gradle plugin

```kotlin
greener {
    springBootJar = file("build/libs/myapp.jar")
    // Always specify an external script - choose one of the examples below
    externalTrainingScriptFile = file("examples/workloads/oha/run.sh")
    warmupDurationSeconds = 30
    measureDurationSeconds = 60
    requestsPerSecond = 50
}
```

```bash
./gradlew measureEnergy
```

---

## Environment variables

All scripts receive these environment variables from the plugin:

| Variable | Example | Description |
|---|---|---|
| `APP_URL`        | `http://localhost:8080` | Base URL of the application |
| `APP_HOST`       | `localhost`             | Host only |
| `APP_PORT`       | `8080`                  | Port only |
| `WARMUP_SECONDS` | `30`                    | Warmup phase duration |
| `MEASURE_SECONDS`| `60`                    | Measurement window duration |
| `TOTAL_SECONDS`  | `90`                    | Sum of warmup + measure |
| `RPS`            | `50`                    | Target requests per second |

---

## oha (recommended for CI)

oha is the recommended default for CI pipelines: it is distributed as a statically
linked binary with no runtime dependencies, produces consistent constant-rate load,
and has a real-time TUI that is disabled automatically with `--no-tui`.

```bash
# Install (Linux - GitHub release)
OHA_VERSION="1.14.0"
curl -fsSL -o /usr/local/bin/oha \
  "https://github.com/hatoo/oha/releases/download/v${OHA_VERSION}/oha-linux-amd64"
chmod +x /usr/local/bin/oha

# Install (macOS)
brew install oha

# Run standalone
oha --no-tui --duration 60s --qps 50 http://localhost:8080/
```

---

## k6

k6 uses a constant-arrival-rate executor which keeps the request rate fixed
regardless of server response time - ideal for reproducible energy measurements.

```bash
# Install (Linux)
sudo gpg --no-default-keyring \
  --keyring /usr/share/keyrings/k6-archive-keyring.gpg \
  --keyserver hkp://keyserver.ubuntu.com:80 \
  --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] \
  https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update && sudo apt-get install k6

# Install (macOS)
brew install k6

# Run standalone
APP_URL=http://localhost:8080 WARMUP_SECONDS=30 MEASURE_SECONDS=60 RPS=20 \
  k6 run examples/workloads/k6/petclinic.js
```

---

## wrk

```bash
# Install (Linux)
sudo apt-get install wrk

# Install (macOS)
brew install wrk

# Run standalone
wrk -t4 -c20 -d60s -s examples/workloads/wrk/petclinic.lua http://localhost:8080
```

---

## wrk2

wrk2 uses a **constant-throughput** model (`-R <rps>`) which avoids the "coordinated
omission" problem and gives more reproducible energy measurements.

```bash
# Build from source
git clone https://github.com/giltene/wrk2 && cd wrk2 && make

# Run standalone
wrk2 -t4 -c20 -d60s -R50 -s examples/workloads/wrk2/petclinic.lua http://localhost:8080
```

---

## Gatling

Gatling provides the richest reporting (HTML, latency percentiles, per-scenario
breakdowns) and supports assertions that can fail the build on SLA violations.

```bash
# Run via the provided script (auto-downloads Gatling bundle, requires Java 17+)
APP_URL=http://localhost:8080 MEASURE_SECONDS=60 RPS=10 \
  bash examples/workloads/gatling/run.sh
```

---

## Locust

Locust is written in Python and supports a flexible user behaviour model.  Use
`constant_throughput` to keep the request rate stable during measurement.

```bash
# Install
pip install locust

# Run standalone (headless)
APP_URL=http://localhost:8080 MEASURE_SECONDS=60 RPS=20 \
  locust --headless --locustfile examples/workloads/locust/locustfile.py
```

---

## Apache Bench (ab)

ab ships with every Apache installation.  It only benchmarks one URL per run
but requires zero extra tooling.

```bash
# Install (Linux)
sudo apt-get install apache2-utils

# Run standalone
ab -n 1200 -c 5 http://localhost:8080/
```

---

## Bombardier

Bombardier is a lightweight Go binary with built-in rate limiting.

```bash
# Install
go install github.com/codesenberg/bombardier@latest

# Run standalone
bombardier --connections 5 --rate 20 --duration 60s http://localhost:8080/
```

---

## VM mode note

When running inside a virtual machine, RAPL power counters are not directly
accessible.  Use **Scaphandre** on the KVM host to obtain real per-VM power data
and feed it to Joular Core via `--vm --vm-power-file`.

> **Never use a mocked or random power value** - it makes energy reports
> meaningless and cannot be used to compare code changes.

See [`examples/vm-setup/README.md`](../vm-setup/README.md) for a complete setup
guide covering Scaphandre virtio-mem (Option A), file-based export (Option B), and
RAPL MSR passthrough (Option C).

