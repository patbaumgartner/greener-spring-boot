# Copilot Instructions — greener-spring-boot

## Project Overview

greener-spring-boot provides Maven and Gradle plugins that measure the energy
consumption of Spring Boot applications using [Joular Core](https://github.com/joular/joularcore).
The plugins start the application, run a configurable training workload, record
power samples, compare against a stored baseline, and generate console + HTML reports.

## Module Structure

| Module | Purpose |
|---|---|
| `greener-spring-boot-core` | Shared library: models, config, readers, comparator, reporters, runners, downloader |
| `greener-spring-boot-maven-plugin` | Maven plugin (`greener:measure`, `greener:update-baseline`) |
| `greener-spring-boot-gradle-plugin` | Gradle plugin (`measureEnergy`, `updateEnergyBaseline`) |
| `examples/` | Workload scripts (wrk, oha, k6, Gatling, …), local simulation, VM setup guides |

## Build & Test

```bash
# Maven (core + maven-plugin)
mvn --batch-mode clean verify

# Gradle plugin (requires core installed in mavenLocal first)
mvn --batch-mode clean install -pl greener-spring-boot-core
cd greener-spring-boot-gradle-plugin && ./gradlew build --no-daemon
```

## Coding Conventions

- **Java 17** — use records, sealed classes, pattern matching where appropriate.
- **Spring Java Format** — the formatter is enforced via `spring-javaformat-maven-plugin`.
  Use tab-based indentation consistent with the formatter output.
- **Builder-style setters** — configuration classes (`JoularCoreConfig`, `TrainingConfig`)
  use fluent `return this` setters, not JavaBean setters.
- **Records for value objects** — `EnergyBaseline`, `EnergyMeasurement`, `EnergyReport`,
  `ComparisonResult`, `WorkloadStats` are all records.
- **Logging** — use `java.util.logging.Logger` in core; Maven uses `getLog()`, Gradle uses
  `getLogger().lifecycle()`.
- **No Lombok** — the project does not use Lombok.

## Testing

- **JUnit 6** with **AssertJ** assertions and **Mockito** for mocks.
- Tests live in `greener-spring-boot-core/src/test/java/`.
- Test class naming: `{ClassName}Test`.
- Use `@TempDir` for file-system tests, `assertThat(…)` (AssertJ) over `assertEquals`.

## Commit Messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<optional scope>): <description>

[optional body]
```

Types: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`, `ci`, `perf`.

## Dependencies

- **Jackson** for JSON serialization of baselines.
- **JUnit 6 / JUnit Jupiter** for testing.
- Dependency updates are managed via **Renovate** (`renovate.json5`) and **Dependabot** (`.github/dependabot.yml`).

## Key Design Decisions

1. **Process-level monitoring first** — Joular Core monitors the Spring Boot JVM by PID;
   JoularJX (method-level) is an optional add-on.
2. **External workload tools preferred** — the built-in HTTP loader is a fallback;
   real measurements should use oha, wrk, k6, Gatling, etc.
3. **VM mode** — for CI/CD and VMs without RAPL, a CPU-time × TDP estimator writes
   power values to a file that Joular Core reads in `--vm` mode.
4. **Auto-download** — Joular Core binaries are auto-downloaded from GitHub Releases
   and cached in `~/.greener/cache/joularcore/`.
