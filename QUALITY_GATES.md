# Quality Gates

This document describes all quality gates enforced in the greener-spring-boot project.

## PR / Push Gates (every commit)

| Gate | Tool | Scope | Enforcement |
|---|---|---|---|
| **Unit tests** | JUnit 5 + AssertJ | core, maven-plugin, gradle-plugin | `mvn verify` / `./gradlew build` — build fails on test failure |
| **Code coverage** | JaCoCo | core | `jacoco:check` — minimum 50% line coverage |
| **Code formatting** | Spring Java Format | all Maven modules | Enforced via `spring-javaformat-maven-plugin` |
| **SpotBugs** | SpotBugs Maven Plugin | core | `spotbugs:check` — high-confidence bugs fail build |
| **PMD** | PMD Maven Plugin | core | `pmd:check` — best practices + error-prone rules |
| **OpenRewrite** | rewrite-maven-plugin | all Maven modules | `rewrite:dryRun` — prevents code quality drift |
| **Security scanning** | CodeQL | all modules | `security-and-quality` query suite via GitHub Actions |

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

## CI Workflow Matrix

| Workflow | Trigger | Gates Executed |
|---|---|---|
| `ci.yml` | push, pull_request | Maven verify, SpotBugs, PMD, OpenRewrite dry-run, Gradle build |
| `codeql.yml` | push to main, PR to main, weekly | CodeQL security-and-quality |
| `energy-baseline.yml` | after CI success on main | Energy measurement + baseline caching |
| `energy-comparison.yml` | pull_request | Energy measurement + comparison comment |
| `validate-workloads.yml` | dispatch, workflow_run, PR | All workload tool smoke tests |
| `release.yml` | manual dispatch | Snapshot or release deployment |

## Adding New Gates

1. Add the plugin to the parent `pom.xml` under `<pluginManagement>`.
2. Configure execution in the module-level `pom.xml` (e.g., `greener-spring-boot-core/pom.xml`).
3. Add a CI step in `.github/workflows/ci.yml` to run the gate.
4. Update this document.
