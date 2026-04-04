# greener-spring-boot ⚡

> Maven and Gradle plugins to measure the energy consumption of Spring Boot applications
> using [Joular Core](https://www.noureddine.org/research/joular/joularcore),
> compare results against a stored baseline, and fail the build on regressions.

[![CI](https://github.com/patbaumgartner/greener-spring-boot/actions/workflows/ci.yml/badge.svg)](https://github.com/patbaumgartner/greener-spring-boot/actions/workflows/ci.yml)
[![CodeQL](https://github.com/patbaumgartner/greener-spring-boot/actions/workflows/codeql.yml/badge.svg)](https://github.com/patbaumgartner/greener-spring-boot/actions/workflows/codeql.yml)
[![Energy Baseline](https://github.com/patbaumgartner/greener-spring-boot/actions/workflows/energy-baseline.yml/badge.svg)](https://github.com/patbaumgartner/greener-spring-boot/actions/workflows/energy-baseline.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-blue)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.x-green)](https://spring.io/projects/spring-boot)
[![Maven Central](https://img.shields.io/badge/Maven%20Central-0.1.0--SNAPSHOT-orange)](https://central.sonatype.com/)
[![GitHub issues](https://img.shields.io/github/issues/patbaumgartner/greener-spring-boot)](https://github.com/patbaumgartner/greener-spring-boot/issues)
[![GitHub stars](https://img.shields.io/github/stars/patbaumgartner/greener-spring-boot)](https://github.com/patbaumgartner/greener-spring-boot/stargazers)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](CONTRIBUTING.md)

---

## Quickstart (2 minutes)

### Maven

```xml
<!-- Add to your Spring Boot project's pom.xml -->
<plugin>
  <groupId>com.patbaumgartner</groupId>
  <artifactId>greener-spring-boot-maven-plugin</artifactId>
  <version>0.1.0</version>
</plugin>
```

```bash
mvn package greener:measure
```

### Gradle

```kotlin
// Add to build.gradle.kts
plugins {
    id("com.patbaumgartner.greener-spring-boot") version "0.1.0"
}
```

```bash
./gradlew bootJar measureEnergy
```

That's it — you'll get an HTML energy report in `target/greener-reports/` (Maven) or `build/greener-reports/` (Gradle).

---

## Who is this for?

| Role | Value |
|---|---|
| **Developer** | Find energy regressions before merge — catch inefficient code in PRs |
| **Platform engineer** | Add an energy policy gate to CI — automated, no manual testing |
| **Engineering manager** | Reduce compute cost drift over time — extra CPU watts = recurring cloud spend |

> **ROI example**: a 12% energy regression on a service handling 10k req/s translates to ~€1,200/year in additional cloud compute costs per instance.

<details>
<summary><strong>More ROI scenarios</strong></summary>

| Scenario | Regression | Scale | Estimated annual cost impact |
|---|---|---|---|
| Internal API (5 instances) | +8% CPU | 2k req/s | ~€480/year |
| Customer-facing service (20 instances) | +15% CPU | 10k req/s | ~€9,600/year |
| Batch processing pipeline | +25% CPU | 4h nightly jobs | ~€2,100/year |
| Microservice fleet (50 services) | +5% avg | mixed | ~€15,000/year |

**How to estimate your own impact**:
1. Run `greener:measure` to establish a baseline energy value (in joules).
2. Introduce a change and re-measure — note the delta percentage.
3. Multiply the delta by your per-instance cloud compute cost × number of instances.

Beyond cost, each watt saved reduces your carbon footprint.  At a typical European
grid intensity of ~300 g CO₂/kWh, a 10 W reduction across 20 instances saves
~525 kg CO₂/year.

</details>

---

## How it works

```mermaid
flowchart TB
    subgraph plugin["greener-spring-boot plugin"]
        direction TB
        A["1. Resolve Joular Core binary<br/><i>auto-download or use provided path</i>"] --> B
        B["2. Start Spring Boot fat-jar"] --> C
        C["3. Wait for readiness probe<br/><code>/actuator/health/readiness → 200</code>"] --> D
        D["4. Start Joular Core<br/><i>monitors PID, writes CSV (W/s)</i>"] --> E
        E["5. Training workload<br/><b>warmup</b> (discard) + <b>measure</b> (record)"] --> F
        F["6. Stop Joular Core & application"] --> G
        G["7. Compute energy<br/><code>E = Σ power × Δt</code>"] --> H
        H["8. Compare against baseline<br/><b>IMPROVED</b> · <b>UNCHANGED</b> · <b>REGRESSED</b>"] --> I
        I["9. Generate reports<br/><i>console + HTML</i>"] --> J
        J{"Regression > threshold?"}
    end

    J -- No --> K["✅ Build passes"]
    J -- Yes --> L["❌ Build fails <i>(if failOnRegression=true)</i>"]

    subgraph workload["Training workload options"]
        W1["Built-in HTTP loader"]
        W2["External script<br/><i>oha, wrk, k6, gatling, …</i>"]
        W3["External command<br/><i>any CLI tool</i>"]
    end
    workload -.-> E

    subgraph power["Power sources (auto-detected)"]
        P1["🔌 RAPL counters<br/><i>bare-metal Intel/AMD</i>"]
        P2["🖥️ Scaphandre VM file<br/><i>KVM host exports power</i>"]
        P3["📊 CPU-time × TDP estimate<br/><i>CI fallback</i>"]
    end
    power -.-> D
```

**[Joular Core](https://www.noureddine.org/research/joular/joularcore)** is a
cross-platform Rust binary that reads hardware power counters:

| Platform | Power source | Notes |
|---|---|---|
| Linux | Intel/AMD RAPL via `powercap` | Most accurate; requires readable counters |
| Windows | [Hubblo RAPL driver](https://github.com/hubblo-org/windows-rapl-driver) | Requires driver installation |
| macOS | `powermetrics` | Requires `sudo` or sudoers config |
| VM / CI | CPU-time × TDP estimation | Automatic fallback; good for relative comparisons |

---

## Project structure

```
greener-spring-boot/
├── greener-spring-boot-core/           Shared library (model, readers, comparator, reporters, runners)
├── greener-spring-boot-maven-plugin/   Maven plugin  (greener:measure, greener:update-baseline)
├── greener-spring-boot-gradle-plugin/  Gradle plugin (measureEnergy, updateEnergyBaseline)
├── examples/                           Workload scripts, VM setup guides, local simulation
└── .github/workflows/
    ├── ci.yml                          Build & test all modules
    ├── codeql.yml                      Static security analysis (CodeQL)
    ├── energy-baseline.yml             Measure baseline on main branch (Spring Petclinic)
    ├── energy-comparison.yml           Measure on PR, compare, post comment
    ├── energy-local-simulation.yml     Baseline + comparison in a single run
    ├── release.yml                     Release to Maven Central and Gradle Plugin Portal
    └── validate-workloads.yml          Smoke-test all example workload scripts
```

---

## Maven plugin

### Minimal configuration

```xml
<plugin>
  <groupId>com.patbaumgartner</groupId>
  <artifactId>greener-spring-boot-maven-plugin</artifactId>
  <version>0.1.0</version>
  <configuration>
    <!-- springBootJar is auto-detected from target/ — set only if needed -->
    <!-- <springBootJar>${project.build.directory}/myapp.jar</springBootJar> -->

    <!-- Training workload -->
    <warmupDurationSeconds>30</warmupDurationSeconds>
    <measureDurationSeconds>60</measureDurationSeconds>

    <!-- Baseline comparison -->
    <baselineFile>${project.basedir}/energy-baseline.json</baselineFile>
    <threshold>10</threshold>          <!-- % regression allowed  -->
    <failOnRegression>false</failOnRegression>
  </configuration>
  <executions>
    <execution>
      <goals><goal>measure</goal></goals>
    </execution>
  </executions>
</plugin>
```

### Run it

```bash
# Measure energy (runs the app, training workload, comparison)
mvn greener:measure

# Save current results as the new baseline
mvn greener:update-baseline
```

### All parameters

| Parameter | Default | Description |
|---|---|---|
| `springBootJar` | *(auto-detected)* | Path to the Spring Boot fat-jar; auto-detected from `target/` if not set |
| `applicationPort` | `8080` | HTTP port |
| `joularCoreBinaryPath` | *(auto-download)* | Path to `joularcore` binary |
| `joularCoreVersion` | `0.0.1-alpha-11` | Version to download |
| `joularCoreComponent` | `cpu` | `cpu`, `gpu`, or `all` |
| `baseUrl` | `http://localhost:8080` | Base URL for training HTTP requests |
| `trainingPaths` | `/`, `/actuator/health`, … | URL paths exercised |
| `requestsPerSecond` | `5` | HTTP request rate |
| `externalTrainingCommand` | *(none)* | External load test command (e.g. `k6 run`) |
| `externalTrainingScriptFile` | *(none)* | Path to an external shell script (e.g. `examples/workloads/oha/run.sh`) |
| `vmMode` | `false` | Enable Joular Core VM mode (no direct RAPL; reads power from `vmPowerFilePath`) |
| `vmPowerFilePath` | *(none)* | File that provides VM power in Watts; updated every second by the host or the estimator script |
| `warmupDurationSeconds` | `30` | Warmup before recording (discarded) |
| `measureDurationSeconds` | `60` | Measurement window |
| `startupTimeoutSeconds` | `120` | Wait for health check |
| `healthCheckPath` | `/actuator/health/readiness` | Health endpoint path (readiness probe) |
| `baselineFile` | `energy-baseline.json` | JSON baseline file |
| `threshold` | `10` | % regression threshold |
| `failOnRegression` | `false` | Fail build if regression > threshold |
| `reportOutputDir` | `target/greener-reports` | HTML report directory |
| `skip` | `false` | Skip execution |

---

## Gradle plugin

### Apply the plugin

```kotlin
plugins {
    id("com.patbaumgartner.greener-spring-boot") version "0.1.0"
}

greener {
    springBootJar = file("build/libs/myapp.jar")
    measureDurationSeconds = 60
    threshold = 10.0
    failOnRegression = false
}
```

### Run it

```bash
./gradlew measureEnergy
./gradlew updateEnergyBaseline
```

---

## CI / CD with Spring Petclinic

The provided GitHub Actions workflows demonstrate the full pipeline using
[Spring Petclinic](https://github.com/spring-projects/spring-petclinic) (`main` branch).

### `energy-baseline.yml`
Runs on every push to `main` (or manually).  Measures energy consumption and
caches `energy-baseline.json` for PR comparisons.

### `energy-comparison.yml`
Runs on every PR.  Restores the `main` baseline, measures energy on the PR
code, and posts a comparison comment:

```
⚡ greener-spring-boot — Energy Report
─────────────────────────────────────
  Baseline (main): 1234.56 J
  Current (PR):    1289.33 J
  Delta:           +4.45%   ≈ UNCHANGED (threshold ±10%)
```

### Power source auto-detection

All CI pipelines detect the best available power source automatically:

| Source | Condition | Accuracy |
|---|---|---|
| **RAPL** (hardware) | `/sys/class/powercap/intel-rapl/.../energy_uj` readable | ★★★ highest |
| **Scaphandre VM file** | `VM_POWER_FILE` env var set + file exists | ★★★ high |
| **CPU-time × TDP** ← CI default | `/proc/stat` readable (any Linux) | ★★ estimated |

On GitHub-hosted runners, GitLab shared runners, and Jenkins agents without
direct hardware access, the third option runs automatically — no configuration
needed.  Results are reproducible on the same runner type and valid for
**relative comparisons** between commits.

For absolute energy accuracy, use a self-hosted bare-metal runner or configure
[Scaphandre on your KVM host](./examples/vm-setup/README.md).

---

## Supported CI systems

| CI System | Config file | Notes |
|---|---|---|
| **GitHub Actions** | `.github/workflows/energy-baseline.yml` / `energy-comparison.yml` | Posts comparison as PR comment |
| **Local / WSL2** | Run the Maven plugin directly | `mvn greener:measure` with `vmMode=true` and the estimator script |

The plugin itself is CI-agnostic — use it in any pipeline that can run Maven or Gradle.
The `ci-cpu-energy-estimator.sh` script works on any Linux with `/proc/stat`.

---

## Hardware requirements

| Platform | Requirement |
|---|---|
| Linux | Intel/AMD CPU with RAPL; `powercap` files readable (`sudo` or ACL) |
| Windows | [Hubblo RAPL driver](https://github.com/hubblo-org/windows-rapl-driver) installed |
| macOS | `powermetrics` (pre-installed); run with `sudo` or configure `sudoers` |

---

## Troubleshooting

### Permission denied reading RAPL counters

On Linux, RAPL energy counters require read access to `/sys/class/powercap/intel-rapl/...`.
As a non-root user, grant access:

```bash
sudo chmod -R a+r /sys/class/powercap/intel-rapl/
```

Or use a `udev` rule for persistence across reboots.  On VMs or CI, RAPL is
unavailable — the plugin falls back to CPU-time × TDP estimation automatically.

### Application does not start within timeout

Increase `startupTimeoutSeconds` (default 120 s):

```xml
<startupTimeoutSeconds>180</startupTimeoutSeconds>
```

Check that the health endpoint is reachable at `http://localhost:<port>/actuator/health/readiness`.
The plugin automatically enables Spring Boot health probes via
`--management.endpoint.health.probes.enabled=true`.

### "No jar found" error

The plugin auto-detects the Spring Boot fat-jar from `target/` (Maven) or
`build/libs/` (Gradle).  Ensure the jar is built first:

```bash
mvn package         # Maven
./gradlew bootJar   # Gradle
```

If multiple jars exist, set the jar path explicitly:

```xml
<springBootJar>${project.build.directory}/myapp.jar</springBootJar>
```

### Energy results vary between runs

Some variance (±5%) is normal due to CPU background activity and thermal
throttling.  For more stable results:

- Use `vmMode=true` with the CPU-time × TDP estimator for relative comparisons.
- Increase `measureDurationSeconds` (longer windows smooth out fluctuations).
- Set a reasonable `threshold` (e.g. 10%) to avoid false-positive regressions.
- Close unrelated CPU-intensive processes during measurement.

### Joular Core download fails

The plugin auto-downloads Joular Core from [GitHub Releases](https://github.com/joular/joularcore/releases)
into `~/.greener/cache/joularcore/`.  If the download fails:

- Check internet connectivity and proxy/firewall settings.
- Download manually and set `joularCoreBinaryPath` to the local path.
- Verify the binary is executable: `chmod +x joularcore`.

---

## Alternatives

greener-spring-boot focuses on **build-integrated, automated energy regression testing** for Spring Boot. Here are other tools in the green software ecosystem:

| Tool | Scope | Approach |
|---|---|---|
| [JoularJX](https://github.com/joular/joularjx) | Java (method-level) | Java agent using Joular Core; per-method energy attribution |
| [Kepler](https://github.com/sustainable-computing-io/kepler) | Kubernetes pods | eBPF + ML models to estimate energy per pod |
| [Scaphandre](https://github.com/hubblo-org/scaphandre) | Host / VM | System-level power monitoring; exports to Prometheus |
| [PowerAPI](https://github.com/powerapi-ng/powerapi) | Processes | Middleware for real-time per-process power monitoring |
| [Green Metrics Tool](https://github.com/green-coding-solutions/green-metrics-tool) | Full stack | End-to-end energy measurement pipelines |
| [codecarbon](https://github.com/mlco2/codecarbon) | Python | Tracks CO₂ emissions from compute; ML-focused |
| [Cloud Carbon Footprint](https://github.com/cloud-carbon-footprint/cloud-carbon-footprint) | Cloud | Estimates carbon from cloud provider billing data |

---

## Contributing

Contributions are welcome! See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

---

## License

Apache License 2.0 — see [LICENSE](LICENSE).
