# ADR-0001: Process-level monitoring first

## Status

Accepted

## Context

The project needs to measure energy consumption of Spring Boot applications.
Two levels of granularity are available:

1. **Process-level** (Joular Core): monitors the entire JVM process by PID;
   reports total power consumption in watts per second.
2. **Method-level** (JoularJX): a Java agent that attributes energy to
   individual methods; higher overhead and more complex setup.

Most CI/CD use cases need a single "total energy used" number for regression
detection. Method-level detail is valuable for diagnosis but is not required
for the primary gate/fail use case.

## Decision

Use Joular Core (process-level monitoring) as the primary and required
measurement engine. JoularJX (method-level) is an optional add-on that users
can enable for deeper analysis.

The core plugin orchestration (start app, start monitor, run workload, stop,
read results) is designed around process-level CSV output. JoularJX results
are read by a separate reader (`JoularJxResultReader`) and merged into the
report when available.

## Consequences

- **Simpler default setup**: users only need the Joular Core binary (Rust,
  auto-downloaded) — no Java agent configuration required.
- **Lower overhead**: process-level monitoring does not instrument bytecode.
- **CI-friendly**: works in VMs and containers with the CPU-time × TDP
  fallback; no JVMTI agent dependencies.
- **Trade-off**: without JoularJX, users cannot see which methods consume
  the most energy — they only see the total per-process number.
- **Extensibility**: adding method-level support later is additive, not
  breaking. The `EnergyReport` model already supports per-method
  `EnergyMeasurement` entries.
