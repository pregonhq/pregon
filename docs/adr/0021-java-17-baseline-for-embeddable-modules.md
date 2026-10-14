# ADR-0021: Java 17 baseline for the embeddable modules
Status: Accepted (supersedes the Java release of ADR-0004)

## Context
ADR-0004 compiles `core` and `spi` for Java 21. Only these modules are embedded in other
applications: the server and the agent run in their own containers with their own JVM, and
adopters that use the REST API, webhooks or SDKs are not affected by their Java version.
Many applications that could embed Pregón still run on Java 17 (for example, everything built on
Spring Boot 3), and Android supports the Java 17 language level but not all Java 21 APIs.

## Decision
`pregon-core` and `pregon-spi` compile with `--release 17`. `pregon-server` and `pregon-agent`
stay on Java 25. Every dependency of `core` and `spi` must run on Java 17 (checked in CI by
building and testing them on a JDK 17). Java 21+ features (virtual threads, pattern matching for
`switch` on sealed types, sequenced collections) are used only in `server` and `agent`.
Going lower (11 or 8) was rejected: it loses `record` and `sealed`, which keep the domain model
clear, for little real reach.

## Consequences
Embeddable in Java 17+ applications and Android hosts. The core domain is written without
Java 21 features; `--release 17` makes the compiler reject them.
