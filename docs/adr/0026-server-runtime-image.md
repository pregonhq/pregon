# ADR-0026: Server image: a linked runtime on Alpine
Status: Accepted (refines ADR-0002)

## Context
The server image was a Spring Boot layered jar on `eclipse-temurin:25-jre-noble`. Measured on
2026-10-16 (arm64): Ubuntu base 110 MB, apt packages 44 MB (fontconfig, locales, ca-certificates),
the full JRE 200 MB, our jar layers 43 MB. The code is a tenth of the image; the rest is a general
purpose operating system and a runtime able to run any Java program, most of which the server
never loads. ADR-0002 promises an image a mini PC pulls and restarts without noticing it.

## Decision
The `pregon-server` image is built in three stages:

1. **Build** on `eclipse-temurin:<jdk>-jdk-alpine-<alpine>`: Maven packages the jar and extracts
   its layers (unchanged).
2. **Link**: `jdeps` lists the JDK modules the classpath references; `jlink` produces a runtime
   with those plus the modules only reached by reflection (`jdk.charsets` for request bodies in
   unusual encodings, `jdk.zipfs` for resource scanning). Debug symbols, man pages and headers are
   stripped; a class-data-sharing archive is generated so startup stays what it was with the stock
   JRE. The second archive, for heaps above 32 GB, is deleted.
3. **Run** on `alpine:<version>`: the linked runtime, the layers, the `pregon` CLI script, a
   non-root user. The healthcheck uses BusyBox `wget`; `read_only` and `tmpfs /tmp` in Compose
   stay as they were.

The module list is computed at every build, so a new dependency that needs another module is
picked up without editing the Dockerfile. Base images are pinned to exact tags under ADR-0014.

### Why Alpine is acceptable here and not everywhere
The server is pure Java: no native libraries beyond the JVM itself, and Temurin ships a musl
build. The capture agent is different: ONNX Runtime (Silero VAD, ADR-0007) ships glibc binaries
only, so the agent image stays on a glibc base. The two Dockerfiles are not meant to converge.

### What was deliberately not done
- **AOT cache / CDS training run** (JDK 25 `-XX:AOTCache`, Spring's `spring.context.exit=onRefresh`):
  a training run starts the context, which runs Flyway and needs a database at build time. Left
  for when startup time matters more than it does today (1.4 s).
- **`jdk.localedata`**: the API speaks ISO instants and English problem details; no localized
  formatting happens in the server. If it ever does, add the module with `--include-locales`.
- **Trimming dependencies**: the 43 MB of jars are Tomcat, Spring, Lettuce/Reactor, Jackson and
  the drivers; nothing there is unused.

## Measured
| | before | after |
|---|---|---|
| Uncompressed layers | 397 MB | 132 MB |
| Compressed (what a registry transfers) | 143 MB | 88 MB |
| `docker images` on Docker Desktop (arm64) | 540 MB | 219 MB |
| `Started PregonServerApplication in` (3 runs each) | 1.37 to 1.44 s | 1.43 to 1.46 s |

Of the 132 MB: Alpine 9, JVM 26, modules 29, CDS archive 14, Spring Boot layers 43, the rest
native libraries of `java.desktop` (pulled in by `java.beans`, which Spring uses).

## Consequences
A third of the pull and a quarter of the disk, same startup, same behavior in the Compose smoke
test (readiness, Valkey failure, `pregon keys`). Costs accepted: stack traces inside JDK classes
have no line numbers; a dependency that reaches a JDK module only by reflection fails at runtime
rather than at build time, which the Compose smoke test in CI is there to catch; Alpine CVE
advisories come from a different tracker than Ubuntu's.
