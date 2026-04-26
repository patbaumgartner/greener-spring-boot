# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **`EnergyMeasurementException` with hint codes.** Failures inside the measurement
  pipeline are wrapped in a typed exception that carries an actionable `Hint`
  (e.g. `EMPTY_OR_MISSING_CSV`, `WORKLOAD_TOOL_MISSING`, `JOULAR_CORE_BINARY_MISSING`).
  Each hint includes a one-line recovery suggestion in the exception message, so
  users see *what to fix* rather than just *what went wrong*. Subclass of
  `IOException`, so existing catch-blocks continue to work.
- **`greener:doctor` / `energyDoctor` preflight command.** New goal/task runs a series
  of environment checks (OS+arch, RAPL `/sys/class/powercap` access, msr kernel module,
  Joular Core binary presence, JoularJX agent path, workload tool on `PATH`, Spring Boot
  fat-jar auto-detection) and prints a PASS / WARN / FAIL report with actionable hints
  for each failing check. Fails the build by default when any check is FAIL; set
  `-Dgreener.doctor.failOnError=false` (Maven) or `--continue` to advisory-only mode.
  Saves minutes that would otherwise be wasted on a misconfigured measurement run.
- **Multi-iteration measurement.** New `iterations` parameter (Maven + Gradle, default
  `1`) runs the measurement window N times back-to-back, archives each iteration's
  Joular Core CSV under `work/iterations/joularcore-output-iter-N.csv`, computes
  `Statistics` across runs, and picks the iteration whose total is closest to the
  median as the representative report. With `iterations >= 2` the comparator
  automatically gains run-to-run statistical power (Welch's t-test + Cohen's d).
- **Throughput-aware regression metric.** New `regressionMetric` parameter
  (`TOTAL_ENERGY` | `ENERGY_PER_REQUEST`). When set to `ENERGY_PER_REQUEST` and both
  the baseline and current run carry workload request counts, the comparator decides
  on energy efficiency (mJ/req) instead of raw Joules &mdash; preventing trivial
  "regressions" that are really throughput improvements. Falls back to
  `TOTAL_ENERGY` automatically when request counts are missing.
- **Idle-baseline subtraction.** New `idleProbeSeconds` parameter (default `0` =
  disabled). When set, the orchestrator measures idle CPU power for N seconds *after*
  Joular Core is started but *before* warmup, then subtracts `idlePowerW × duration`
  from each workload measurement (clamped at zero). Surfaces the energy attributable
  to your code rather than the host.
- **`EnergyBaseline` schema v1.2.** Optional `WorkloadStats` is persisted alongside
  the report so future comparisons can normalise per-request without re-measuring
  the baseline. v1.0 / v1.1 baselines load unchanged.
- **Statistical regression detection.** New `Statistics` value record captures
  per-iteration descriptive statistics (mean, stddev, min, max, median, 95% CI half-width),
  computes Welch's two-sample t-test and Cohen's d effect size. `EnergyComparator`
  now uses these to make a regression decision: a change is flagged only when
  `|Cohen's d| ≥ 0.5` (medium effect) **and** `p < 0.05` **and** percentage delta
  exceeds the threshold. This eliminates false positives from large-N statistical
  significance on tiny effects and false negatives from a noise floor that swallows
  real regressions. Single-iteration runs automatically fall back to the percentage
  rule.
- **JoularJX over-attribution renormalisation.** New `JoularJxRenormalizer` rescales
  method-level energies so they sum to the authoritative Joular Core process total
  (`factor = processEnergyJ / Σ methodEnergyJ`). Method shares are preserved; absolute
  numbers become comparable to baselines, energy budgets, and other process-level
  reports. Renormalisation is automatic, only triggers on over-attribution
  (under-attribution is left alone to avoid inventing energy), and is logged as a
  one-line diagnostic when applied.
- **`EnergyBaseline` schema v1.1.** Embedded `EnergyReport` may now carry a
  `totalEnergyStats` field. `BaselineManager` reads v1.0 baselines unchanged
  (missing stats default to `Statistics.empty()`) and writes v1.1 going forward.
  Forward-compatible: unknown future JSON fields are silently ignored.
- **Zero-dependency Quickstart.** README now shows a `curl`-only fallback workload
  command so the very first install needs no external tool.
- JoularJX method-level energy monitoring as an optional add-on to process-level
  Joular Core measurements; new `joularJxAgentPath` and `joularJxConfigPath`
  parameters (Maven + Gradle) to enable per-method energy granularity
- `externalTrainingCommand` parameter (Maven + Gradle) for inline workload commands
  (e.g. `oha -n 500 -c 10 ${APP_URL}/actuator/health`) without a separate script file
- `topN` parameter (Maven + Gradle) to limit the number of top energy-consuming
  methods shown in the HTML report (default: 20)
- `autoUpdateBaseline` parameter (Maven + Gradle) to auto-promote measurement results
  to the baseline file after a successful run, eliminating separate `update-baseline` calls
- `timestampReports` parameter (Maven + Gradle) to append a `yyyyMMdd-HHmmss` timestamp
  to the report output directory and create a `latest` symlink
- `commitSha` and `branch` parameters (Maven + Gradle) to record VCS metadata in the
  baseline file when auto-updating
- `skip` parameter for Gradle plugin to skip execution entirely
- SHA-256 digest verification for auto-downloaded Joular Core binaries using checksums
  published in the GitHub Release API
- `MeasurementOrchestrator` to coordinate the full measurement lifecycle (warmup,
  measurement, result processing, baseline comparison, report generation) shared by
  both Maven and Gradle plugins

### Changed

- **BREAKING: Default `iterations` is now `5`** (was `1`). Single-iteration runs are
  still supported for quick smoke tests; the new default makes every CI run produce
  enough samples for Welch's t-test + Cohen's d gating out of the box. Set
  `<iterations>1</iterations>` (Maven) or `iterations.set(1)` (Gradle) to restore
  the previous behaviour.
- **BREAKING: Default `regressionMetric` is now `ENERGY_PER_REQUEST`** (was
  `TOTAL_ENERGY`). Throughput improvements no longer masquerade as energy
  regressions when the workload tool reports request counts. Falls back to
  `TOTAL_ENERGY` automatically when request counts are missing on either side.
- **BREAKING: Removed legacy headerless-CSV path in `JoularCoreResultReader`.**
  Modern Joular Core (≥0.0.1-beta-1) always emits a header row. Files without one
  now fail fast with `EnergyMeasurementException(EMPTY_OR_MISSING_CSV)`.
- **BREAKING: API surface tightened.** Removed `EnergyBaseline.of(report)` and
  `EnergyBaseline.of(report, sha, branch)` &mdash; use the canonical
  `of(report, commitSha, branch, workloadStats)`. Removed the 14-arg legacy
  `MeasurementConfig` constructor &mdash; use the 17-arg canonical form. Removed the
  7-arg `MeasurementOrchestrator.processBaselineComparison(...)` overload &mdash;
  pass `regressionMetric` and `currentWorkload` explicitly. **On-disk baseline
  JSON files written by older releases continue to load unchanged** &mdash; the
  break is API-only, not data-format.
- **`ComparisonResult` extended** with three new fields &mdash; `pValue`, `cohenD`
  (both nullable `Double`) and `statisticalDecision` (boolean). Convenience
  7-arg and 10-arg constructors remain for callers that don't care about the
  statistical fields.
- **`EnergyReport` extended** with a `totalEnergyStats` field. The compact
  constructor coerces a `null` `totalEnergyStats` to `Statistics.empty()`, so
  single-iteration callers and JSON deserialisers can omit it.
- **Joular Core reader error messages** are now multi-line, list likely root causes
  (RAPL kernel-module access, unsupported CPU, VM/container without `--vm`), and
  point users at the next concrete step. Existing test contracts (substring match
  on "no valid power samples") are preserved.


- `AppArgsBuilder` to assemble Spring Boot application arguments with automatic
  health-probe injection, Actuator shutdown endpoint, and port extraction
- `JoularCoreProbe` to auto-detect which power component (CPU/GPU) delivers non-zero
  readings and augment JoularJX config accordingly
- `MeasurementConfig` record to centralise shared configuration for the orchestrator
- `MeasurementResult` record aggregating energy report, baseline comparison, workload
  stats, optional method-level reports, and HTML report path
- `MethodLevelReports` record combining filtered (app-only) and unfiltered (all methods)
  JoularJX energy reports for method-level analysis
- `BaselineManager.discoverLatestReport(Path)` to scan report subdirectories for the
  most recently modified `latest-energy-report.json`
- `PluginDefaults.resolveToolName(File, String)` to derive a workload tool name from
  the configured training script or command
- `PluginDefaults.buildTimestampedDir(Path)` and `createLatestLink(Path, String)` for
  timestamped report directory management
- `setup-energy-measurement` composite GitHub Action to detect power source, start
  the CI CPU power estimator, and build/cache the Joular Core binary
- `energy-local-simulation.yml` workflow for local multi-tool simulation runs
- `detect-power-source.sh` utility for automatic RAPL / Scaphandre / estimator detection
- `CODEOWNERS` file for automatic PR review assignment
- `QUALITY_GATES.md` documenting SpotBugs, PMD, CodeQL, and test coverage gates
- SpotBugs and PMD quality-gate executions added to the Maven plugin module
- Joularjx simulation scripts (`joularjx-simulation.sh`, `joularjx-simulation.ps1`)
- Playwright-based HTML report integration tests (`HtmlReporterPlaywrightTest`)
- Tests for `MeasurementOrchestrator`, `AppArgsBuilder`, `MeasurementResult`,
  `ApplicationRunner`, `JoularCoreRunner`, `TrainingRunner`, and `JoularCoreDownloader`
- Tests for `discoverLatestReport`, `resolveToolName`, `buildTimestampedDir`, and
  `createLatestLink`
- Negative tests for `ExternalToolOutputParser` covering malformed and partial output
- `TrainingConfigTest` with tests for defaults, fluent setters, and edge cases
- Scaphandre Windows installer link in README hardware requirements
- Simulation scripts section in README "Supported CI systems"
- Changelog and versioning instructions in copilot-instructions.md
- Post-release version bump step in `release.yml` to automatically commit the next
  `-SNAPSHOT` version after a release, preventing manual bookkeeping mistakes
- `good first issue` and `help wanted` guidance in CONTRIBUTING.md to help new contributors
  find entry points
- Mockito test dependency to Gradle plugin (`mockito-junit-jupiter`) for parity with Maven modules
- No-baseline warning banner in energy PR comparison comments when no baseline exists

### Changed

- `externalTrainingCommand` or `externalTrainingScriptFile` is now **required** -
  the plugin fails at runtime if neither is configured
- Default Joular Core version updated from `0.0.1-alpha-11` to `0.0.1-beta-1`
- Measurement logic extracted from Maven and Gradle plugins into shared
  `MeasurementOrchestrator` in core module
- `AppArgsBuilder` extracted from `PluginDefaults` into its own class
- `JoularCoreProbe` extracted from `PluginDefaults` into its own class
- `discoverLatestReport` logic extracted from `UpdateBaselineMojo` and `UpdateBaselineTask`
  into shared `BaselineManager`
- `resolveToolName` logic extracted from `MeasureEnergyMojo` and `MeasureEnergyTask`
  into shared `PluginDefaults`
- Energy workflows refactored to use `setup-energy-measurement` composite action,
  extracting `GREENER_VERSION` env var to eliminate version drift
- Joular Core downloader now verifies downloads against SHA-256 digests from the
  GitHub Release API; mismatched binaries are deleted
- `TrainingRunner` now supports inline commands and captures stdout output for
  `ExternalToolOutputParser` workload statistics extraction
- Gradle `UpdateBaselineTask` error handling aligned with Maven: warn+return
  instead of throwing an exception
- Simulation scripts simplified to use `autoUpdateBaseline=true` instead of separate
  `update-baseline` calls
- README configuration sections split into minimal and full examples for both
  Maven and Gradle
- Expanded "Supported CI systems" table in README with GitLab CI and Jenkins rows
- `baseUrl` and `requestsPerSecond` parameter descriptions clarified as env vars for external scripts
- README quickstart snapshot warning replaced with a prominent GitHub-rendered `[!WARNING]` alert
- Maven Central badge in README switched from a hardcoded static badge to a dynamic version badge
- Redundant `spring-javaformat:validate` CI step removed (already runs as part of `mvn verify`)
- `SECURITY.md` supported versions table updated to list only released versions

### Removed

- Built-in HTTP loader (`TrainingRunner.runBuiltInHttpLoader()`) - use external scripts instead
- `trainingPaths` parameter from Maven and Gradle plugins
- `paths` and `concurrency` fields from `TrainingConfig`
- `WorkloadStats.builtIn()` factory method
- Duplicate `discoverLatestReport()` methods in `UpdateBaselineMojo` and `UpdateBaselineTask`
- Duplicate `resolveToolName()` body in `MeasureEnergyMojo` and `MeasureEnergyTask`
- `SharedMojoUtils` class (logic moved to `PluginDefaults` and `MeasurementOrchestrator`)

### Fixed

- CodeQL `NumberFormatException` alerts in `ExternalToolOutputParser` (safe parsing helpers)
- CodeQL relative-path-command alert in `TrainingRunner` (absolute path resolution)
- CodeQL workflow fixed to build with Java 17 (plugins target Java 17; Java 25 is
  only needed by the Spring Petclinic energy workflows)
- PMD violations in `MeasureEnergyMojo`: stack traces now preserved in catch blocks
- Em dashes replaced with standard dashes in all documentation files
- Method-level energy card now merges application methods from the filtered JoularJX report
  into the all-methods table so the "Show App Only" filter works correctly
- Method-level "Total Energy" label renamed to "Total Energy (all threads)" to avoid
  confusion with the process-level total in the Measurement Summary card
- Explanatory note added to the Method-Level Energy card when the JoularJX total exceeds
  the Joular Core process-level energy, clarifying the difference in measurement scope
- PowerShell `$OhaScript` variable scope fix in `local-simulation.ps1`
- `Join-Path` three-argument incompatibility fix in PowerShell simulation scripts
- Unicode character encoding fixed for Windows PowerShell 5.x compatibility

## [0.1.0] - 2026-04-03

### Added

- Energy measurement of Spring Boot applications via [Joular Core](https://github.com/joular/joularcore)
- Maven plugin with `greener:measure` and `greener:update-baseline` goals
- Gradle plugin with `measureEnergy` and `updateEnergyBaseline` tasks
- External workload script support for custom load profiles
- Example workload scripts for eight popular load-testing tools: oha, wrk, wrk2, k6, Apache Benchmark (ab), bombardier, Locust, and Gatling
- Energy baseline comparison with configurable threshold to detect regressions
- HTML report and console reporter for energy measurement results
- VM-level energy monitoring mode via [Scaphandre](https://github.com/hubblo-org/scaphandre)
- Automatic download of the Joular Core binary at build time
- CI workflow (`ci.yml`) for continuous integration on every push and pull request
- Energy baseline workflow (`energy-baseline.yml`) for capturing reference measurements
- Energy comparison workflow (`energy-comparison.yml`) for regression detection
- Validate-workloads workflow (`validate-workloads.yml`) to smoke-test all example scripts
- CodeQL workflow (`codeql.yml`) for static security analysis
- Dependabot configuration for automated dependency updates
- Renovate configuration for automated dependency updates
- CPU-time × TDP software energy estimator (`ci-cpu-energy-estimator.sh` / `.ps1`) for
  environments without RAPL access (GitHub Actions, CI pipelines, WSL2)
- Three-tier automatic power source detection in CI pipelines:
  RAPL (hardware) → Scaphandre VM file → CPU-time × TDP estimation

### Changed

- Gradle wrapper upgraded from 8.7 to 9.4.1
- Jackson upgraded to 2.21.2, JUnit to 6.0.3, Mockito to 5.23.0, AssertJ to 3.27.7
- Maven plugin tooling upgraded: compiler-plugin 3.15.0, surefire 3.5.5,
  plugin-plugin 3.15.2, plugin-api 3.9.14
- Fixed RAPL availability check: now tests `energy_uj` file readability
  instead of directory existence (the directory exists on VMs but files are unreadable)
- Energy measurement workflows no longer skip on GitHub-hosted runners;
  the CPU-time × TDP estimator runs automatically instead

[Unreleased]: https://github.com/patbaumgartner/greener-spring-boot/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/patbaumgartner/greener-spring-boot/releases/tag/v0.1.0
