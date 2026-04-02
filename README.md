# greener-spring-boot ⚡

> Maven and Gradle plugins to measure the energy consumption of Spring Boot applications
> using [Joular Core](https://www.noureddine.org/research/joular/joularcore),
> compare results against a stored baseline, and fail the build on regressions.

[![CI](https://github.com/patbaumgartner/greener-spring-boot/actions/workflows/ci.yml/badge.svg)](https://github.com/patbaumgartner/greener-spring-boot/actions/workflows/ci.yml)

---

## How it works

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  greener-spring-boot plugin (Maven / Gradle)                                 │
│                                                                              │
│  1. Start Spring Boot application (fat-jar)                                  │
│  2. Wait for /actuator/health → 200 OK                                       │
│  3. Start Joular Core  ──monitors PID──►  writes CSV (power W / second)      │
│  4. Run training workload  (warmup + measure)                                │
│     • Built-in HTTP loader  OR  external command (k6, wrk, …)               │
│  5. Stop Joular Core & application                                           │
│  6. Read CSV  →  energy = Σ power × 1 s                                     │
│  7. Compare against baseline  →  IMPROVED / UNCHANGED / REGRESSED           │
│  8. Write console + HTML report                                              │
│  9. Optionally fail the build if regression exceeds threshold                │
└──────────────────────────────────────────────────────────────────────────────┘
```

**[Joular Core](https://www.noureddine.org/research/joular/joularcore)** is a
cross-platform Rust binary that reads hardware power counters:
- Linux  — Intel/AMD RAPL via the `powercap` interface
- Windows — via Hubblo's RAPL driver
- macOS  — via `powermetrics`

---

## Project structure

```
greener-spring-boot/
├── greener-spring-boot-core/           Shared library (model, readers, comparator, reporters, runners)
├── greener-spring-boot-maven-plugin/   Maven plugin  (greener:measure, greener:update-baseline)
├── greener-spring-boot-gradle-plugin/  Gradle plugin (measureEnergy, updateEnergyBaseline)
└── .github/workflows/
    ├── ci.yml                          Build & test all modules
    ├── energy-baseline.yml             Measure baseline on main branch (Spring Petclinic)
    └── energy-comparison.yml           Measure on PR, compare, post comment
```

---

## Maven plugin

### Minimal configuration

```xml
<plugin>
  <groupId>com.patbaumgartner</groupId>
  <artifactId>greener-spring-boot-maven-plugin</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <configuration>
    <!-- Required: path to the Spring Boot fat-jar -->
    <springBootJar>${project.build.directory}/myapp.jar</springBootJar>

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
| `springBootJar` | *(required)* | Path to the Spring Boot fat-jar |
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
| `healthCheckPath` | `/actuator/health` | Health endpoint path |
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
    id("com.patbaumgartner.greener-spring-boot") version "0.1.0-SNAPSHOT"
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
| **GitLab CI/CD** | `.gitlab-ci.yml` | Artifacts published per pipeline |
| **Jenkins** | `Jenkinsfile` | Requires `temurin-25` JDK + `maven-3` tool configured |
| **Local / WSL2** | Run the Maven plugin directly | `mvn greener:measure` with `vmMode=true` and the estimator script |

---

## Hardware requirements

| Platform | Requirement |
|---|---|
| Linux | Intel/AMD CPU with RAPL; `powercap` files readable (`sudo` or ACL) |
| Windows | [Hubblo RAPL driver](https://github.com/hubblo-org/windows-rapl-driver) installed |
| macOS | `powermetrics` (pre-installed); run with `sudo` or configure `sudoers` |

---

## License

Apache License 2.0 — see [LICENSE](LICENSE).
