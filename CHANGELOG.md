# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-04-03

### Added

- Energy measurement of Spring Boot applications via [Joular Core](https://github.com/joular/joularcore)
- Maven plugin with `greener:measure` and `greener:update-baseline` goals
- Gradle plugin with `measureEnergy` and `updateEnergyBaseline` tasks
- Built-in HTTP loader for basic workload generation
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
