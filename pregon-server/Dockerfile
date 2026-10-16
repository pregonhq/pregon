# syntax=docker/dockerfile:1
# Build context: the repository root (multi-module Maven build).
# Three stages (ADR-0026): build the jar, link a runtime with only the JDK modules it uses, and
# copy both onto a plain Alpine base. The server has no native libraries, so musl is fine here;
# the agent (ONNX Runtime) must stay on a glibc base.
FROM eclipse-temurin:25.0.4.1_1-jdk-alpine-3.24 AS build
WORKDIR /workspace

# Poms first, so the dependency layer is reused while only sources change.
COPY mvnw pom.xml ./
COPY .mvn/ .mvn/
COPY pregon-spi/pom.xml pregon-spi/
COPY pregon-core/pom.xml pregon-core/
COPY pregon-server/pom.xml pregon-server/
COPY pregon-agent/pom.xml pregon-agent/

COPY pregon-spi/src/ pregon-spi/src/
COPY pregon-core/src/ pregon-core/src/
COPY pregon-server/src/ pregon-server/src/

# Tests run in CI; the image build only packages.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -pl pregon-server -am package -DskipTests \
 && java -Djarmode=tools -jar pregon-server/target/pregon-server-*.jar \
      extract --layers --launcher --destination /extracted

# The runtime contains the modules the classpath references (jdeps) plus the ones only reached by
# reflection: extended charsets for request bodies and the zip filesystem for resource scanning.
# --strip-debug only affects JDK classes; our stack traces keep their line numbers. The CDS archive
# for heaps above 32 GB (no compressed oops) is dropped: the server runs with a few hundred MB.
RUN modules="$(jdeps --multi-release 25 --ignore-missing-deps --print-module-deps \
      -cp '/extracted/dependencies/BOOT-INF/lib/*' \
      /extracted/application/BOOT-INF/classes /extracted/dependencies/BOOT-INF/lib/*.jar 2>/dev/null | tail -1)" \
 && echo "jlink modules: ${modules}" \
 && jlink --add-modules "${modules},jdk.charsets,jdk.zipfs" \
      --strip-debug --no-man-pages --no-header-files --compress zip-6 \
      --generate-cds-archive \
      --output /runtime \
 && rm /runtime/lib/server/classes_nocoops.jsa

FROM alpine:3.24.2

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH=${JAVA_HOME}/bin:${PATH}
COPY --from=build /runtime ${JAVA_HOME}

RUN addgroup -S pregon && adduser -S -D -H -s /sbin/nologin -G pregon pregon
WORKDIR /app

# One layer per Spring Boot layer, from least to most frequently changed.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./

# `pregon keys ...` manages API keys inside the container (ADR-0018).
COPY --chmod=755 <<'SCRIPT' /usr/local/bin/pregon
#!/bin/sh
exec java -cp /app org.springframework.boot.loader.launch.JarLauncher "$@"
SCRIPT

USER pregon
EXPOSE 8080

# BusyBox wget fails on a 503, and the body check guards against a 200 that is not UP.
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=3 \
  CMD ["sh", "-c", "wget -qO- http://127.0.0.1:8080/actuator/health/readiness | grep -q '\"status\":\"UP\"'"]

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
