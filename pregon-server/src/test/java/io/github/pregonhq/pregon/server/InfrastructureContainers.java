package io.github.pregonhq.pregon.server;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL and Valkey for integration tests, same images as {@code deploy/docker-compose.yml}.
 *
 * <p>The containers are exposed through the {@code PREGON_*} variables, not through Spring
 * properties, so the tests also cover the mapping in {@code application.yaml}.
 */
public final class InfrastructureContainers {

    // Every test context keeps its own connection pool for the whole run; the default 100 connections
    // ran out once ingestion added its contexts.
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withCommand("postgres", "-c", "max_connections=300");

    static final GenericContainer<?> VALKEY =
            new GenericContainer<>("valkey/valkey:9.1.2").withExposedPorts(6379);

    static {
        POSTGRES.start();
        VALKEY.start();
    }

    private InfrastructureContainers() {
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("PREGON_DB_URL", POSTGRES::getJdbcUrl);
        registry.add("PREGON_DB_USER", POSTGRES::getUsername);
        registry.add("PREGON_DB_PASSWORD", POSTGRES::getPassword);
        registry.add("PREGON_REDIS_URL",
                () -> "redis://" + VALKEY.getHost() + ":" + VALKEY.getMappedPort(6379));
    }
}
