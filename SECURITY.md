# Security Policy

## Supported Versions

| Version       | Supported |
| ------------- | --------- |
| 0.2.x         | ✅ Yes |
| 0.1.x         | ✅ Yes |

## Reporting a Vulnerability

Please **do not** open a public GitHub issue for security vulnerabilities.

Instead, use one of the following channels:

1. **GitHub Private Security Advisory** (preferred):
   [https://github.com/patbaumgartner/greener-spring-boot/security/advisories/new](https://github.com/patbaumgartner/greener-spring-boot/security/advisories/new)

2. **Email**: [pat.baumgartner@gmail.com](mailto:pat.baumgartner@gmail.com)

Please include as much detail as possible - steps to reproduce, affected versions, and potential impact.

**Response commitments:**

- Acknowledgement within **5 business days**
- Fix timeline communicated within **10 business days**

## Scope

### In Scope

- Plugin code in `greener-spring-boot-core`, `greener-spring-boot-maven-plugin`, and `greener-spring-boot-gradle-plugin`
- CSV parsing and energy baseline comparison logic
- Joular Core binary auto-download mechanism
- Process management and lifecycle handling

### Out of Scope

- The [Joular Core](https://github.com/joular/joularcore) binary itself (report upstream)
- Spring Boot framework (report to [Spring Security](https://spring.io/security))
- Third-party load-testing tools (oha, wrk, wrk2, k6, ab, bombardier, Locust, Gatling)

## Preferred Languages

English
