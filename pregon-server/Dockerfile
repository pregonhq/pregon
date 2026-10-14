# Build context: the repository root (multi-module Maven build).
FROM eclipse-temurin:25.0.4.1_1-jdk-noble AS build
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

FROM eclipse-temurin:25.0.4.1_1-jre-noble

RUN groupadd --system pregon && useradd --system --gid pregon --no-create-home --shell /usr/sbin/nologin pregon
WORKDIR /app

# One layer per Spring Boot layer, from least to most frequently changed.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./

USER pregon
EXPOSE 8080

# The JRE image has no curl or wget; bash can speak HTTP over /dev/tcp.
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=3 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health/readiness HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
