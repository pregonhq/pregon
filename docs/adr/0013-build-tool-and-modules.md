# ADR-0013: Maven multi-module build
Status: Accepted

## Context
The project needs one build for several JVM artifacts with different rules: `core` and `spi`
must stay free of Spring and compile for Java 17 (ADR-0021), while `server` uses Spring Boot 4 on Java 25.
The artifacts will be published to Maven Central (ADR-0011).

## Decision
Maven, with the Maven Wrapper (`./mvnw`) so contributors need only a JDK.
Modules: `pregon-spi`, `pregon-core`, `pregon-server`, `pregon-agent`.
The root `pom.xml` is a plain parent: it does **not** inherit `spring-boot-starter-parent`.
Only `pregon-server` imports the `spring-boot-dependencies` BOM, so Spring versions and plugins
can never leak into `core` or `spi`. `core` and `spi` compile with `--release 17`; the other
modules with `--release 25`. The STT sidecar is Python and lives in `stt-sidecar/` with its own
Dockerfile; it is not a Maven module.

## Consequences
Familiar tooling, first-class Maven Central publishing. CI must also build the Python sidecar.
