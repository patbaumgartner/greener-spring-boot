# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- **`greener:doctor` / `energyDoctor` now actually check the workload tool.** Both
  goals advertise a "workload tool on `PATH`" check, but each read a separate
  `workloadCommand` option that the README never documented — and on the Gradle side
  the task property was never wired to the extension at all, making the check
  unreachable through the `greener { }` DSL. With the normal setup (a single
  `externalTrainingCommand`) the check silently did not run, so the doctor reported a
  healthy environment for a tool that was not installed. Both front-ends now fall back
  to the configured `externalTrainingCommand` and probe its first token;
  `workloadCommand` remains as an explicit override.
- **A missing Joular Core binary now carries its hint.** `JoularCoreRunner.start()`
  threw a bare `IOException`, so the `JOULAR_CORE_BINARY_MISSING` hint had no throw
  site anywhere in the codebase and users never saw its recovery guidance. It now
  throws `EnergyMeasurementException` with that hint.
- **Regression delta is now labelled with the metric it was computed on.** With the
  default `regressionMetric=ENERGY_PER_REQUEST` the comparator computes the delta on
  millijoules-per-request, but both reporters printed that percentage next to the
  *total energy in Joules*. A run that doubled total energy while doubling throughput
  rendered as `Baseline 100.00 J / Current 200.00 J / Delta -50.00% / IMPROVED` —
  self-contradictory. The delta now carries its unit and, for per-request comparisons,
  the two mJ/req values it was derived from are shown alongside it.
- **Statistical evidence is now visible.** `pValue` and `cohenD` were computed for
  multi-iteration runs but never rendered, so a Welch's t-test decision was
  indistinguishable from a plain threshold decision. Both reporters now show them.
- **`PowerSource.detect()` no longer claims RAPL without checking.** Outside `vmMode`
  it returned `RAPL` unconditionally, stamping "hardware energy counters – high
  accuracy" on reports produced by CI runners, containers and WSL2 that have no
  readable counters — while `greener:doctor` on the same host reported RAPL missing.
  It now probes `/sys/class/powercap/intel-rapl*/energy_uj` and degrades to
  `ESTIMATED`.
- **The external-workload timeout now works.** `TrainingRunner` drained the child's
  output inline before calling `waitFor(timeout)`; since the read returns only at EOF,
  the timeout could never fire for a workload that hangs. Output is now drained on a
  separate thread. A timeout also destroys the whole process tree — previously only the
  `sh -c` wrapper was killed and the load generator kept running, corrupting every
  later measurement window.
- **Joular Core is verified before it is cached.** The downloaded binary was moved to
  its final cache path and marked executable *before* the SHA-256 check, so a failure
  in between left an unverified executable at the path the next run short-circuits to.
  Verification now happens at the temporary path. Concurrent builds no longer collide
  on a shared `<asset>.tmp` file, and `GITHUB_TOKEN`/`GH_TOKEN` is sent to the Release
  API so rate-limited runners can still download.
- **Startup failures are actionable.** `ApplicationRunner` threw a bare
  `RuntimeException`; it now throws `EnergyMeasurementException` with the
  `APPLICATION_NOT_READY` hint. Health-check polling also tolerates every transient
  `IOException` rather than only `ConnectException`/`HttpTimeoutException`, so a server
  still binding its connector no longer aborts startup.
- **Method-level HTML report is bounded by `topN`.** It previously rendered one row per
  call branch; a 40 000-branch application produced an 11.8 MB report (now 16 KB).
- `DoctorMojo` writes through Maven's logger instead of `System.out`, so `-q` and log
  redirection work.
- `MeasureEnergyMojo` restores the thread interrupt flag when the build is cancelled.
- `EnergyComparator` no longer scans current measurements once per baseline method
  (was O(baseline x current) over reports with tens of thousands of entries).
- `JoularCoreRunner.start()` no longer throws `NullPointerException` when
  `outputCsvPath` is a bare filename with no parent directory.
- Off-by-one in `ConsoleReporter` name truncation made truncated names one character
  wider than their column.

### Added

- **`externalTrainingTimeoutSeconds`** (Maven + Gradle). `TrainingConfig` already
  supported a workload timeout but neither plugin ever set it, so it was always
  unbounded — while the `WORKLOAD_TIMEOUT` hint told users to increase an option that
  did not exist. Defaults to `0` (wait indefinitely).
- Workload failures now carry their typed hints (`WORKLOAD_TIMEOUT`, `WORKLOAD_FAILED`,
  `WORKLOAD_TOOL_MISSING`) instead of a bare `IOException`. Six of the seven
  `EnergyMeasurementException.Hint` constants were previously never thrown.

### Changed

- The `pre-commit` hook no longer runs `versions:update-properties` /
  `useLatestVersions`, and no longer `git add`s the whole worktree. It rewrote
  `pom.xml` and `gradle.properties` on every commit, so untested dependency bumps rode
  along inside unrelated commits and version pairs documented as "must match" drifted
  apart (`pmd` 7.17.0 vs 7.24.0, `jacoco` 0.8.15 vs 0.8.14 — both realigned).
  Dependency upgrades remain Renovate's and Dependabot's job.

- Upgrade Jackson from 2.21.3 to 3.1.4. The `groupId` for all Jackson Core
  artifacts changes from `com.fasterxml.jackson.core` to `tools.jackson.core`.
  `ObjectMapper` construction now uses the immutable `JsonMapper.builder()` pattern.
  `jackson-datatype-jsr310` is no longer a separate dependency (merged into
  `jackson-databind` 3.x). Jackson 3 exceptions are unchecked (`JacksonException`
  extends `RuntimeException`).

## [0.2.0] - 2026-05-28

### Changed

- Replace JoularJX with joularcode-java (`github.com/joular/joularcode-java`) for
  method-level energy monitoring. joularcode-java is the successor of JoularJX and
  requires JoularCore running with ring buffer support (`-r` flag, enabled
  automatically when `joularCodeJavaAgentPath` is configured).
- Rename plugin parameters `joularJxAgentPath`/`joularJxConfigPath` to
  `joularCodeJavaAgentPath`/`joularCodeJavaConfigPath` in both Maven and Gradle plugins.
- Add ring buffer support to `JoularCoreConfig` (`-r` flag enabled automatically
  when the joularcode-java agent is configured).
- Fix project URLs: replace `noureddine.org/research/joular/joularcore` with
  `github.com/joular/joularcore`.

### Added

#### Measurement quality

- **Multi-iteration measurement.** New `iterations` parameter (Maven + Gradle)
  runs the measurement window N times back-to-back, archives each iteration's
  Joular Core CSV under `work/iterations/joularcore-output-iter-N.csv`, computes
  `Statistics` across runs, and picks the iteration whose total is closest to
  the median as the representative report.
- **Statistical regression detection.** New `Statistics` value record captures
  per-iteration descriptive statistics (mean, stddev, min, max, median, 95% CI
  half-width) and computes Welch's two-sample t-test plus Cohen's d effect size.
  `EnergyComparator` flags a regression only when `|Cohen's d| >= 0.5` AND
  `p < 0.05` AND percentage delta exceeds the threshold. Single-iteration runs
  fall back to the percentage rule automatically.
- **Throughput-aware regression metric.** New `regressionMetric` parameter
  (`TOTAL_ENERGY` | `ENERGY_PER_REQUEST`). When set to `ENERGY_PER_REQUEST` and
  both runs carry workload request counts, the comparator decides on energy
  efficiency (mJ/req) instead of raw Joules. Falls back to `TOTAL_ENERGY` when
  request counts are missing on either side.
- **Idle-baseline subtraction.** New `idleProbeSeconds` parameter (default `0` =
  disabled) measures idle CPU power for N seconds after Joular Core starts but
  before warmup, then subtracts `idlePowerW * duration` from each workload
  measurement (clamped at zero) - surfacing the energy attributable to your
  code rather than the host.

#### Reporting

- **HTML trend chart.** The HTML report embeds an inline-SVG line chart of
  recent runs whenever a baseline path is configured. Each measurement appends
  to a trend file (capped at 100 entries) with total Joules,
  energy-per-request (when available), commit SHA, and branch. The trend file
  lives next to the configured `baselineFile` and is named by replacing
  `.json` with `-trend.json` (e.g. `energy-baseline.json` →
  `energy-baseline-trend.json`), so each baseline gets its own independent
  history. Total energy is drawn in cyan, energy-per-request in dashed
  magenta on a secondary axis, with hoverable tooltips. Persistence failures
  never fail the build.
- **Joular Code Java method-level energy monitoring** as an optional add-on to
  process-level Joular Core measurements via new `joularCodeJavaAgentPath` and
  `joularCodeJavaConfigPath` parameters (Maven + Gradle).
- `topN` parameter (Maven + Gradle) to limit the number of top energy-consuming
  methods shown in the HTML report (default: 20).

#### Diagnostics and developer experience

- **`greener:doctor` / `energyDoctor` preflight command.** New goal/task runs
  environment checks (OS+arch, RAPL `/sys/class/powercap` access, msr kernel
  module, Joular Core binary, Joular Code Java agent path, workload tool on `PATH`,
  Spring Boot fat-jar auto-detection) and prints a PASS / WARN / FAIL report
  with actionable hints. Fails the build by default; set
  `-Dgreener.doctor.failOnError=false` (Maven) or `--continue` for advisory
  mode.
- **`EnergyMeasurementException` with hint codes.** Failures are wrapped in a
  typed exception that carries an actionable `Hint` (e.g.
  `EMPTY_OR_MISSING_CSV`, `WORKLOAD_TOOL_MISSING`, `JOULAR_CORE_BINARY_MISSING`)
  so users see what to fix, not just what went wrong. Subclass of `IOException`
  for catch-block compatibility.
- **Joular Core reader error messages** are now multi-line, list likely root
  causes (RAPL kernel-module access, unsupported CPU, VM/container without
  `--vm`), and point users at the next concrete step.

#### Plugin parameters

- `externalTrainingCommand` parameter (Maven + Gradle) for inline workload
  commands (e.g. `oha -n 500 -c 10 ${APP_URL}/actuator/health`) without a
  separate script file.
- `autoUpdateBaseline` parameter (Maven + Gradle) to auto-promote results to
  the baseline after a successful run, eliminating separate `update-baseline`
  calls.
- `timestampReports` parameter (Maven + Gradle) to append a
  `yyyyMMdd-HHmmss` timestamp to the report output directory and create a
  `latest` symlink.
- `commitSha` and `branch` parameters (Maven + Gradle) to record VCS metadata
  in the baseline file when auto-updating.
- `skip` parameter for the Gradle plugin to skip execution entirely.

#### Baseline schema

- **`EnergyBaseline` schema v1.1**: embedded `EnergyReport` may now carry a
  `totalEnergyStats` field. Older v1.0 baselines load unchanged (missing stats
  default to `Statistics.empty()`).
- **`EnergyBaseline` schema v1.2**: optional `WorkloadStats` is persisted
  alongside the report so future comparisons can normalise per-request without
  re-measuring the baseline. v1.0 / v1.1 baselines load unchanged.

#### Internals

- `MeasurementOrchestrator` to coordinate the full measurement lifecycle
  (warmup, measurement, result processing, baseline comparison, report
  generation) shared by both Maven and Gradle plugins.
- `AppArgsBuilder` to assemble Spring Boot application arguments with automatic
  health-probe injection, Actuator shutdown endpoint, and port extraction.
- `JoularCoreProbe` to auto-detect which power component (CPU/GPU) delivers
  non-zero readings and augment Joular Code Java config accordingly.
- `MeasurementConfig` record to centralise shared orchestrator configuration.
- `MeasurementResult` record aggregating energy report, baseline comparison,
  workload stats, optional method-level reports, and HTML report path.
- `MethodLevelReports` record combining filtered (app-only) and unfiltered
  (all methods) Joular Code Java energy reports.
- `BaselineManager.discoverLatestReport(Path)` to scan report subdirectories
  for the most recently modified `latest-energy-report.json`.
- `PluginDefaults` helpers: `resolveToolName(File, String)`,
  `buildTimestampedDir(Path)`, and `createLatestLink(Path, String)`.
- SHA-256 digest verification for auto-downloaded Joular Core binaries using
  checksums published in the GitHub Release API.

#### CI / GitHub Actions

- `setup-energy-measurement` composite GitHub Action to detect power source,
  start the CI CPU power estimator, and build/cache the Joular Core binary.
- `energy-local-simulation.yml` workflow for local multi-tool simulation runs.
- `detect-power-source.sh` utility for automatic RAPL / Scaphandre / estimator
  detection.
- Post-release version bump step in `release.yml` to automatically commit the
  next `-SNAPSHOT` version after a release.

#### Documentation

- **Zero-dependency Quickstart**: README now shows a `curl`-only fallback
  workload command so the very first install needs no external tool.
- **Windows notes** section in README covering three gotchas: (1) `mvn.cmd` /
  `cmd.exe` does not support UNC paths - build from a local drive; (2) Joular Code Java
  final `total/methods/` CSVs are not produced on Windows because
  `Process.destroy()` maps to `TerminateProcess` and bypasses JVM shutdown hooks
  (per-second `runtime/methods/` CSVs are still written); (3) workload
  `run.sh` scripts use `apt-get` / `brew`, so on Windows pre-install tools via
  `choco`.
- README configuration sections split into minimal and full examples for both
  Maven and Gradle.
- "Supported CI systems" table expanded with GitLab CI and Jenkins rows.
- Scaphandre Windows installer link added to hardware requirements.
- Simulation scripts section added to README.
- **Energy trend chart** section in README documenting the inline-SVG trend
  chart, the trend-file schema (named `<baselineStem>-trend.json` next to
  the baseline), the rolling 100-entry cap, and how to persist the trend
  file alongside the baseline in CI.
- Changelog and versioning instructions added to `copilot-instructions.md`.
- `good first issue` and `help wanted` guidance added to `CONTRIBUTING.md`.

#### Quality gates and tests

- `CODEOWNERS` file for automatic PR review assignment.
- `QUALITY_GATES.md` documenting SpotBugs, PMD, CodeQL, and test coverage
  gates.
- SpotBugs and PMD quality-gate executions added to the Maven plugin module.
- Joular Code Java simulation scripts (`joularcode-simulation.sh`,
  `joularcode-simulation.ps1`).
- Playwright-based HTML report integration tests
  (`HtmlReporterPlaywrightTest`).
- Tests for `MeasurementOrchestrator`, `AppArgsBuilder`, `MeasurementResult`,
  `ApplicationRunner`, `JoularCoreRunner`, `TrainingRunner`,
  `JoularCoreDownloader`, `discoverLatestReport`, `resolveToolName`,
  `buildTimestampedDir`, and `createLatestLink`.
- Negative tests for `ExternalToolOutputParser` covering malformed and partial
  output.
- `TrainingConfigTest` with defaults, fluent setters, and edge cases.
- Mockito test dependency added to the Gradle plugin
  (`mockito-junit-jupiter`) for parity with Maven modules.
- No-baseline warning banner in energy PR comparison comments when no baseline
  exists.

### Changed

#### Breaking

- **Default `iterations` is now `5`** (was `1`). Single-iteration runs are
  still supported for quick smoke tests; the new default makes every CI run
  produce enough samples for Welch's t-test + Cohen's d gating out of the box.
  Set `<iterations>1</iterations>` (Maven) or `iterations.set(1)` (Gradle) to
  restore the previous behaviour.
- **Default `regressionMetric` is now `ENERGY_PER_REQUEST`** (was
  `TOTAL_ENERGY`). Throughput improvements no longer masquerade as energy
  regressions when the workload tool reports request counts. Falls back to
  `TOTAL_ENERGY` automatically when request counts are missing on either side.
- **Removed legacy headerless-CSV path in `JoularCoreResultReader`.** Modern
  Joular Core (>= 0.0.1-beta-1) always emits a header row. Files without one
  now fail fast with `EnergyMeasurementException(EMPTY_OR_MISSING_CSV)`.
- **API surface tightened.** Removed `EnergyBaseline.of(report)` and
  `EnergyBaseline.of(report, sha, branch)` - use the canonical
  `of(report, commitSha, branch, workloadStats)`. Removed the 14-arg legacy
  `MeasurementConfig` constructor - use the 17-arg canonical form. Removed the
  7-arg `MeasurementOrchestrator.processBaselineComparison(...)` overload -
  pass `regressionMetric` and `currentWorkload` explicitly. **On-disk baseline
  JSON files written by older releases continue to load unchanged** - the
  break is API-only, not data-format.
- **`externalTrainingCommand` or `externalTrainingScriptFile` is now
  required** - the plugin fails at runtime if neither is configured.
- **Trend file name derived from baseline file name.** The historical
  `greener-energy-trend.json` (a single fixed name) has been replaced with
  `<baselineStem>-trend.json` so every baseline keeps its own history. With
  the default `energy-baseline.json` the new file is
  `energy-baseline-trend.json`. Existing CI users either need to rename the
  cached file or accept that history starts fresh on the next run; CI
  workflow snippets and example simulation scripts in this repo have been
  updated accordingly.

#### Defaults

- Default Joular Core version updated from `0.0.1-alpha-11` to `0.0.1-beta-4`.
  Binaries are not yet published for `0.0.1-beta-4` upstream; users without a
  cached binary should pin `joularCoreVersion` back to `0.0.1-beta-1` or build
  Joular Core from source - the simulation scripts already do this via cargo.

#### Records and data classes

- **`ComparisonResult` extended** with `pValue`, `cohenD` (both nullable
  `Double`) and `statisticalDecision` (boolean). Convenience 7-arg and 10-arg
  constructors remain for callers that don't care about the statistical
  fields.
- **`EnergyReport` extended** with a `totalEnergyStats` field. The compact
  constructor coerces a `null` `totalEnergyStats` to `Statistics.empty()`, so
  single-iteration callers and JSON deserialisers can omit it.

#### Refactoring

- Measurement logic extracted from Maven and Gradle plugins into shared
  `MeasurementOrchestrator` in core module.
- `AppArgsBuilder` and `JoularCoreProbe` extracted from `PluginDefaults` into
  their own classes.
- `discoverLatestReport` logic extracted from `UpdateBaselineMojo` and
  `UpdateBaselineTask` into shared `BaselineManager`.
- `resolveToolName` logic extracted from `MeasureEnergyMojo` and
  `MeasureEnergyTask` into shared `PluginDefaults`.
- `TrainingRunner` now supports inline commands and captures stdout output for
  `ExternalToolOutputParser` workload statistics extraction.
- Gradle `UpdateBaselineTask` error handling aligned with Maven: warn+return
  instead of throwing an exception.
- Joular Core downloader now verifies downloads against SHA-256 digests from
  the GitHub Release API; mismatched binaries are deleted.

#### CI

- Energy workflows refactored to use `setup-energy-measurement` composite
  action, extracting `GREENER_VERSION` env var to eliminate version drift.
- Simulation scripts simplified to use `autoUpdateBaseline=true` instead of
  separate `update-baseline` calls.
- Redundant `spring-javaformat:validate` CI step removed (already runs as part
  of `mvn verify`).

#### Documentation

- README quickstart snapshot warning replaced with a prominent GitHub-rendered
  `[!WARNING]` alert.
- Maven Central badge in README switched from a hardcoded static badge to a
  dynamic version badge.
- `baseUrl` and `requestsPerSecond` parameter descriptions clarified as env
  vars for external scripts.
- `SECURITY.md` supported versions table updated to list only released
  versions.

### Removed

- Built-in HTTP loader (`TrainingRunner.runBuiltInHttpLoader()`) - use external
  scripts instead.
- `trainingPaths` parameter from Maven and Gradle plugins.
- `paths` and `concurrency` fields from `TrainingConfig`.
- `WorkloadStats.builtIn()` factory method.
- Duplicate `discoverLatestReport()` methods in `UpdateBaselineMojo` and
  `UpdateBaselineTask`.
- Duplicate `resolveToolName()` body in `MeasureEnergyMojo` and
  `MeasureEnergyTask`.
- `SharedMojoUtils` class (logic moved to `PluginDefaults` and
  `MeasurementOrchestrator`).

### Fixed

- CodeQL `NumberFormatException` alerts in `ExternalToolOutputParser` (safe
  parsing helpers).
- CodeQL relative-path-command alert in `TrainingRunner` (absolute path
  resolution).
- CodeQL workflow fixed to build with Java 17 (plugins target Java 17; Java
  25 is only needed by the Spring Petclinic energy workflows).
- PMD violations in `MeasureEnergyMojo`: stack traces now preserved in catch
  blocks.
- Method-level energy card now merges application methods from the filtered
  Joular Code Java report into the all-methods table so the "Show App Only" filter
  works correctly.
- Method-level "Total Energy" label renamed to "Total Energy (all threads)"
  to avoid confusion with the process-level total in the Measurement Summary
  card.
- Explanatory note added to the Method-Level Energy card when the Joular Code Java
  total exceeds the Joular Core process-level energy, clarifying the
  difference in measurement scope.
- PowerShell `$OhaScript` variable scope fix in `local-simulation.ps1`.
- `Join-Path` three-argument incompatibility fix in PowerShell simulation
  scripts.
- Unicode character encoding fixed for Windows PowerShell 5.x compatibility.
- Em dashes replaced with standard dashes in all documentation files.
- Shell simulation scripts (`local-simulation.sh`, `all-tools-simulation.sh`,
  `joularcode-simulation.sh`) now refuse to run on native Windows under Git Bash
  / MSYS / Cygwin and point users at the matching `.ps1` companion or WSL2,
  preventing confusing late failures from Linux-only paths and `joularcore-linux-*`
  asset downloads.
- Broken `{@link EnergyReport}` Javadoc reference in `MeasureEnergyMojo`
  replaced with the fully-qualified link, removing the Maven build warning.
- `JoularCoreDownloader` HTTP 404 errors now include an actionable hint
  pointing users at pinning the previous version (`0.0.1-beta-4`) or
  building Joular Core from source via `cargo build --release` and
  configuring `joularCoreBinaryPath`, instead of just reporting the bare
  HTTP status.

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

[Unreleased]: https://github.com/patbaumgartner/greener-spring-boot/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/patbaumgartner/greener-spring-boot/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/patbaumgartner/greener-spring-boot/releases/tag/v0.1.0
