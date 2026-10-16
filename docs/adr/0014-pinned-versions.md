# ADR-0014: Pinned versions and update policy
Status: Accepted

## Context
Versions must be exact and reproducible. Brand-new releases sometimes ship regressions, and
libraries managed by Spring Boot are tested together as a set.

## Decision
- A version is adopted only after it has been public for at least 14 days.
- In `pregon-server`, libraries managed by the Spring Boot BOM keep Boot's version; they are not
  overridden unless an ADR says why.
- Dependabot proposes updates; each one goes through a PR with green CI.

Versions verified on 2026-10-14 against official sources:

| Component | Version | Notes |
|---|---|---|
| JDK (build and runtime) | Eclipse Temurin 25.0.4.1 | `core`/`spi` also built and tested on Temurin 17.0.20 in CI (ADR-0021); the server image links its own runtime from the Alpine JDK (ADR-0026) |
| Alpine (server runtime image) | 3.24.2 | released 2026-09-18; see ADR-0026 |
| Spring Boot | 4.1.1 | OSS support until 2027-07-31 (4.0 ends 2026-12-31) |
| Spring Framework | 7.0.9 | managed by Boot |
| Flyway | 12.4.0 | managed by Boot (13.x exists but Boot 4.1 is tested with 12.x) |
| Testcontainers | 2.0.5 | managed by Boot |
| JUnit Jupiter | 6.0.3 | Boot's version, also used by `core`/`spi` (JUnit 6 requires Java 17) |
| PostgreSQL JDBC | 42.7.13 | managed by Boot |
| ONNX Runtime (Java) | 1.30.0 | 1.31.0 is too recent |
| Silero VAD model | v6.2.3 | ONNX file downloaded on first start, checked by SHA-256 |
| CloudEvents Java SDK | 5.0.0 | 5.1.0 is too recent; Java 17 bytecode, valid for `core` |
| PostgreSQL (image) | 18.6 | supported until 2030-11 |
| Valkey (image) | 9.1.2 | see ADR-0019 |
| faster-whisper | 1.2.1 | sidecar; Python deps pinned in a lock file under the same 14-day rule |

Spring AI (2.0.x, Boot 4 compatible) is noted for later phases, inside adapters only (ADR-0012).
No OpenAPI code generator is pinned yet; it is chosen with the first implemented endpoint.

## Consequences
Reproducible builds; updates are deliberate and reviewable.
