# ADR-0004: Framework-agnostic core and plugin SPI
Status: Accepted (Java release superseded by ADR-0021: now 17)

## Decision
`core` and `spi` are pure Java (release 21), no Spring, minimal dependencies, hexagonal.
Spring Boot 4 / Java 25 only in `server` and adapters. Sources, detectors and sinks are plugins
discovered via `ServiceLoader`. `spi` follows strict semantic versioning.
## Consequences
Embeddable in non-Spring and older-JVM applications; community can add plugins without touching core.
