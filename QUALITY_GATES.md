# Quality Gates

This document describes all quality gates enforced in the greener-spring-boot project.

---

## PR / Push Gates (every commit)

| Gate | Tool | Scope | Enforcement |
|---|---|---|---|
| **Unit tests** | JUnit Jupiter + AssertJ | core, maven-plugin, gradle-plugin | `mvn verify` / `./gradlew build` - build fails on test failure |
| **Code coverage** | JaCoCo | core, maven-plugin, gradle-plugin | `jacoco:check` - minimum 80% line coverage for core, 40% for maven-plugin, 55% for gradle-plugin |
| **Code formatting** | Spring Java Format | all modules | `spring-javaformat:validate` (Maven) / `checkFormat` (Gradle) - build fails on violations |
| **SpotBugs** | SpotBugs | core, maven-plugin, gradle-plugin | `spotbugs:check` - high-confidence bugs fail build (effort=Max, threshold=High) |
| **PMD** | PMD | core, maven-plugin, gradle-plugin | `pmd:check` / `pmdMain` - best practices + error-prone rules |
| **OpenRewrite** | OpenRewrite | all modules | `rewrite:dryRun` (Maven) / `rewriteDryRun` (Gradle) - prevents code quality drift |
| **Security scanning** | CodeQL | all modules | `security-and-quality` query suite via GitHub Actions |

---

## Scheduled Gates

| Gate | Tool | Schedule | Enforcement |
|---|---|---|---|
| **Dependency vulnerabilities** | OWASP dependency-check | Weekly (Mon 03:00 UTC) + on build-file changes | `dependency-scan.yml` - fails on CVSS >= 7 |
| **SBOM generation** | CycloneDX | Same run as above | `dependency-scan.yml` - `bom.json` uploaded as an artifact |
| **Security scanning** | CodeQL | Push/PR to main + weekly (Sun 02:00 UTC) | `security-and-quality` query suite |

The dependency-check and CycloneDX plugins are configured in the root `pom.xml`
but bound to no lifecycle phase, so `mvn verify` does not run them - keeping the
inner build loop fast. `dependency-scan.yml` is what actually executes them.

---

## On-demand Gates

| Gate | Tool | Command | Description |
|---|---|---|---|
| **OpenRewrite apply** | rewrite-maven-plugin | `mvn rewrite:run` | Apply code quality recipes |
| **Dependency vulnerabilities** | OWASP dependency-check | `mvn dependency-check:check` | Fail on CVEs with CVSS >= 7 |
| **SBOM generation** | CycloneDX | `mvn cyclonedx:makeBom` | Generate CycloneDX SBOM in JSON format |
| **Semgrep scan** | Semgrep | `semgrep --config .semgrep.yml` | Custom Java security + quality rules |
| **JMH benchmarks** | JMH | See below | Performance trend testing for core paths |

### Running JMH benchmarks

```bash
mvn -pl greener-spring-boot-core test-compile exec:java \
    -Dexec.mainClass="com.patbaumgartner.greener.core.benchmark.CoreBenchmark" \
    -Dexec.classpathScope="test"
```

Benchmarks cover: `ExternalToolOutputParser` (oha, wrk, k6 parsing) and `EnergyComparator`.

---

## CI Workflow Matrix

| Workflow | Trigger | Gates Executed |
|---|---|---|
| `ci.yml` | push, pull_request | Maven verify, SpotBugs, PMD, OpenRewrite dry-run, Gradle build |
| `codeql.yml` | push to main, PR to main, weekly | CodeQL security-and-quality |
| `energy-baseline.yml` | after CI success on main | Energy measurement + baseline caching |
| `energy-comparison.yml` | pull_request | Energy measurement + comparison comment |
| `validate-workloads.yml` | dispatch, workflow_run, PR | All workload tool smoke tests |
| `dependency-scan.yml` | weekly, dispatch, build-file changes | OWASP dependency-check (CVSS >= 7) + CycloneDX SBOM |
| `release.yml` | manual dispatch | Snapshot or release deployment |

---

## Adding New Gates

1. Add the plugin to the parent `pom.xml` under `<pluginManagement>`.
2. Configure execution in the module-level `pom.xml` (e.g., `greener-spring-boot-core/pom.xml`).
3. Add a CI step in `.github/workflows/ci.yml` to run the gate.
4. Update this document.

---

## Contributor Note: Java Version Requirements

SpotBugs, PMD, and OpenRewrite require **Java 17** to analyse the compiled
bytecode. If you are developing with a newer JDK (e.g. Java 21+), these gates
may fail locally with `Unsupported class file major version` errors.

Workarounds:

- Use Java 17 to run `mvn verify`. The CI pipeline uses Java 17 and is the
  authoritative result.
- If your system JDK is newer, run the quality gates through a Java 17 toolchain
  or use a version manager (`sdkman`, `mise`, `jenv`) to switch:
  ```bash
  sdk use java 17.0.18-tem && mvn --batch-mode clean verify
  ```
