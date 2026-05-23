# Copilot Instructions - greener-spring-boot

## Project Overview

greener-spring-boot provides Maven and Gradle plugins that measure the energy
consumption of Spring Boot applications using [Joular Core](https://github.com/joular/joularcode-java).
The plugins start the application, run a configurable training workload, record
power samples, compare against a stored baseline, and generate console + HTML reports.

## Module Structure

| Module | Purpose |
|---|---|
| `greener-spring-boot-core` | Shared library: models, config, readers, comparator, reporters, runners, downloader |
| `greener-spring-boot-maven-plugin` | Maven plugin (`greener:measure`, `greener:update-baseline`) |
| `greener-spring-boot-gradle-plugin` | Gradle plugin (`measureEnergy`, `updateEnergyBaseline`) |
| `examples/` | Workload scripts (wrk, oha, k6, Gatling, …), local simulation, VM setup guides |

### Core Package Layout

```
com.patbaumgartner.greener.core
├── baseline/        BaselineManager - load / save energy-baseline.json
│                    RunEntryStore - persist / load AggregatedRunEntry JSON files
├── comparator/      EnergyComparator - diff report vs baseline
├── config/          JoularCoreConfig, TrainingConfig, PluginDefaults,
│                    AppArgsBuilder, JoularCoreProbe
├── downloader/      JoularCoreDownloader - auto-download Joular Core binaries
│                    with SHA-256 verification
├── model/           EnergyReport, EnergyBaseline, EnergyMeasurement,
│                    ComparisonResult, WorkloadStats, AggregatedRunEntry,
│                    MethodLevelReports, MeasurementResult, PowerSource (enum)
├── reader/          JoularCoreResultReader, JoularJxResultReader
├── orchestrator/    MeasurementOrchestrator - coordinates warmup, measurement,
│                    result processing, baseline comparison, and report generation
├── reporter/        ConsoleReporter, HtmlReporter
└── runner/          ApplicationRunner, JoularCoreRunner, TrainingRunner,
                     ExternalToolOutputParser
```

### Key Classes

- **`PluginDefaults`** - shared utility used by both Maven and Gradle plugins
  for `buildRunId()`, `resolvePowerSource(boolean vmMode)`, `normalise(String)`,
  `autoDetectJar(Path, String...)`,
  `formatBaselineUpdateSummary(...)`, `saveAndLogBaseline(...)`,
  `resolveToolName(File, String)`, `buildTimestampedDir(Path)`,
  `createLatestLink(Path, String)`, `validateMeasureDuration(int)`,
  and `validateExternalScript(File)`.
- **`AppArgsBuilder`** - builds the effective Spring Boot application argument list;
  handles health probe injection, Actuator shutdown endpoint, and port extraction
  from the base URL. Extracted from `PluginDefaults`.
- **`JoularCoreProbe`** - probes the Joular Core binary to discover which power
  component delivers non-zero readings; optionally augments a JoularJX config
  file with the detected parameters. Extracted from `PluginDefaults`.
- **`BaselineManager`** - saves/loads `EnergyBaseline` JSON files and discovers
  the most recent report via `discoverLatestReport(Path)`.
- **`RunEntryStore`** - persists and loads `AggregatedRunEntry` JSON files so that
  multi-tool simulation runs can be collected into a single aggregated report.
- **`MeasurementOrchestrator`** - shared measurement workflow used by both Maven
  and Gradle plugins; coordinates warmup, measurement, result processing, baseline
  comparison, and report generation. Returns a `MeasurementResult` from
  `processAndReport()`. Accepts a configurable `topN` for the HTML reporter.
- **`PowerSource`** - enum (`RAPL`, `VM_FILE`, `ESTIMATED`, `UNKNOWN`)
  with `detect(boolean vmMode)` and `fromString(String)`.
- **`ExternalToolOutputParser`** - extracts request counts from stdout of
  oha, wrk, wrk2, bombardier, ab, k6, Gatling, and Locust.
- **`TrainingRunner`** - supports external scripts and inline commands;
  captures stdout for `ExternalToolOutputParser`.
- **`HtmlReporter`** - generates self-contained HTML reports; supports
  single-tool and multi-tool aggregated reports via `generateAggregatedReport()`.
  Method-level card merges app methods from the filtered report into the all-methods
  table and shows an explanatory note when JoularJX totals exceed process-level energy.
- **`MethodLevelReports`** - record combining filtered (app-only) and unfiltered
  (all methods) JoularJX energy reports for method-level analysis.
- **`AggregatedRunEntry`** - record combining tool name, report, workload
  stats, and comparison for multi-tool aggregated reports.
- **`MeasurementResult`** - record aggregating energy report, baseline comparison,
  workload stats, optional method-level reports, and HTML report path; returned
  by `MeasurementOrchestrator.processAndReport()`.

## Build & Test

```bash
# Maven (core + maven-plugin)
mvn --batch-mode clean verify

# Gradle plugin (requires core installed in mavenLocal first)
mvn --batch-mode clean install -pl greener-spring-boot-core
cd greener-spring-boot-gradle-plugin && ./gradlew build --no-daemon
```

## Coding Conventions

- **Java 17** - use records, sealed classes, pattern matching where appropriate.
- **Spring Java Format** - enforced in CI via `spring-javaformat:validate` (Maven) and
  `checkFormat` (Gradle). Use tab-based indentation consistent with the formatter output.
- **Builder-style setters** - configuration classes (`JoularCoreConfig`, `TrainingConfig`)
  use fluent `return this` setters, not JavaBean setters.
- **Records for value objects** - `EnergyBaseline`, `EnergyMeasurement`, `EnergyReport`,
  `ComparisonResult`, `WorkloadStats`, `AggregatedRunEntry`, `MethodLevelReports`,
  `MeasurementResult` are all records.
- **Logging** - use `java.util.logging.Logger` in core; Maven uses `getLog()`, Gradle uses
  `getLogger().lifecycle()`.
- **No Lombok** - the project does not use Lombok.

## Testing

- **JUnit Jupiter** with **AssertJ** assertions and **Mockito** for mocks.
- Tests live in `greener-spring-boot-core/src/test/java/`.
- Test class naming: `{ClassName}Test`.
- Use `@TempDir` for file-system tests, `assertThat(…)` (AssertJ) over `assertEquals`.
- Model record tests verify immutability, validation, and factory methods.
- Reporter tests capture stdout (`ConsoleReporter`) or generate temp files (`HtmlReporter`).
- Parser tests use real tool output samples (`ExternalToolOutputParserTest`).

## Commit Messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<optional scope>): <description>

[optional body]
```

Types: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`, `ci`, `perf`.

## Dependencies

- **Jackson** for JSON serialization of baselines.
- **JUnit Jupiter** for testing.
- Dependency updates are managed via **Renovate** (`renovate.json5`) and **Dependabot** (`.github/dependabot.yml`).

## CI / GitHub Actions

- **Pin actions by full commit SHA** - never use mutable tags like `@v6`.
  Keep the version tag as a trailing comment for readability.
  Example: `uses: actions/checkout@de0fac2e4500dabe0009e67214ff5f5447ce83dd # v6`
- **Renovate** manages SHA digest updates for GitHub Actions (`github-actions` manager).
- **Dependabot** manages Maven and Gradle dependency updates only.

## Key Design Decisions

1. **Process-level monitoring first** - Joular Core monitors the Spring Boot JVM by PID;
   JoularJX (method-level) is an optional add-on.
2. **External workload tools only** - measurements use oha, wrk, k6, Gatling, etc.
   via `externalTrainingScriptFile` or `externalTrainingCommand`.
3. **VM mode** - for CI/CD and VMs without RAPL, a CPU-time × TDP estimator writes
   power values to a file that Joular Core reads in `--vm` mode.
4. **Auto-download** - Joular Core binaries are auto-downloaded from GitHub Releases
   and cached in `~/.greener/cache/joularcore/`. Downloads are verified against
   SHA-256 digests published in the GitHub Release API.

## Changelog & Versioning

- **CHANGELOG.md** must be updated before every release with all notable changes
  grouped under the new version heading.
- After a release, bump the version in all `pom.xml` files and `gradle.properties`
  to the next `-SNAPSHOT` version.
- Follow [Keep a Changelog](https://keepachangelog.com/) format with sections:
  `Added`, `Changed`, `Removed`, `Fixed`.
- The current development version is maintained as `## [Unreleased]` at the top.
