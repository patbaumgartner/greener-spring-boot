# Contributing to greener-spring-boot

Thank you for taking the time to contribute! 🌱⚡

The following is a set of guidelines for contributing to **greener-spring-boot**.
These are mostly guidelines, not rules — use your best judgement and feel free to
propose changes to this document in a pull request.

---

## Table of contents

1. [Code of Conduct](#code-of-conduct)
2. [How can I contribute?](#how-can-i-contribute)
3. [Development setup](#development-setup)
4. [Building the project](#building-the-project)
5. [Running the tests](#running-the-tests)
6. [Submitting a pull request](#submitting-a-pull-request)
7. [Coding conventions](#coding-conventions)
8. [CI / GitHub Actions](#ci--github-actions)
9. [Commit message style](#commit-message-style)

---

## Code of Conduct

This project is governed by the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md).
By participating, you are expected to uphold this code.
Please report unacceptable behaviour to the maintainers.

---

## How can I contribute?

### Reporting bugs

- Search [existing issues](https://github.com/patbaumgartner/greener-spring-boot/issues) first.
- Use the **Bug report** issue template and fill in every section.
- Include the plugin version, Java version, OS, and the full error output.

### Suggesting enhancements

- Search existing issues and discussions before opening a new one.
- Use the **Feature request** issue template.
- Explain the problem you are trying to solve and why the existing behaviour
  does not satisfy it.

### Contributing code

1. Fork the repository and create a feature branch off `main`.
2. Make your changes (see the sections below).
3. Open a pull request against `main` and fill in the PR template.

---

## Development setup

### Prerequisites

| Tool | Minimum version | Notes |
|---|---|---|
| JDK | 17 | Temurin or any OpenJDK distribution |
| Maven | 3.9 | Used for all Maven modules |
| Gradle | 8.x | Wrapper is included in `greener-spring-boot-gradle-plugin/` |

### Clone

```bash
git clone https://github.com/patbaumgartner/greener-spring-boot.git
cd greener-spring-boot
```

### IDE

The project imports cleanly into IntelliJ IDEA and VS Code.
An `.editorconfig` is included to ensure consistent formatting across editors.

---

## Building the project

### Maven modules (core + Maven plugin)

```bash
mvn clean install
```

### Gradle plugin

The Gradle plugin depends on the Maven modules, so build those first:

```bash
# From the repo root
mvn install -DskipTests

# Then build the Gradle plugin
cd greener-spring-boot-gradle-plugin
./gradlew build
```

### All modules in one shot

```bash
mvn install && (cd greener-spring-boot-gradle-plugin && ./gradlew build)
```

---

## Running the tests

### Maven tests

```bash
mvn test
```

### Gradle plugin tests

```bash
cd greener-spring-boot-gradle-plugin
./gradlew test
```

### All tests

```bash
mvn verify && (cd greener-spring-boot-gradle-plugin && ./gradlew check)
```

---

## Submitting a pull request

1. **One concern per PR.** Keep PRs focused — a bug fix should not also refactor
   unrelated code.
2. **Tests are required.** New behaviour must be covered by tests; bug fixes should
   include a regression test.
3. **Green CI.** All GitHub Actions checks must pass before a PR is merged.
4. **Changelog entry.** Add an entry to `CHANGELOG.md` under `[Unreleased]`.
5. **Signed-off commits** are encouraged but not required.

---

## Coding conventions

- **Java 17** language features are fine.
- Formatting is enforced by **Spring Java Format** (`spring-javaformat-maven-plugin`).
  It produces tab-based indentation for Java source files.
- Public API methods and classes must have Javadoc.
- Prefer immutable value objects (`record`) for model classes.
- Use `java.util.logging` (JUL) — no additional logging frameworks.
- Keep the dependency footprint minimal. Do not add a dependency if the JDK
  already provides the functionality.

---

## CI / GitHub Actions

- **Pin actions by full commit SHA** — never reference actions using mutable
  tags like `@v6`. Always use the full 40-character commit SHA and add the
  version tag as a trailing comment:
  ```yaml
  uses: actions/checkout@de0fac2e4500dabe0009e67214ff5f5447ce83dd # v6
  ```
- Renovate automatically proposes PRs when action digests are updated.

---

## Commit message style

This project follows the
[Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) spec:

```
<type>(<scope>): <short description>

[optional body]

[optional footer(s)]
```

**Types:** `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`, `ci`, `perf`

**Examples:**

```
feat(core): add per-request energy-efficiency metric to WorkloadStats
fix(maven-plugin): handle missing baseline file gracefully
docs: document VM power file setup for KVM guests
chore(deps): update Jackson to 2.17.1
```

---

## Questions?

Open a [Discussion](https://github.com/patbaumgartner/greener-spring-boot/discussions)
if you are unsure whether something is a bug or have a general question about
the project.
