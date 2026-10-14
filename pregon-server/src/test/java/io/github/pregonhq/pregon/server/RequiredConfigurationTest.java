package io.github.pregonhq.pregon.server;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** ADR-0020: a missing connection or secret stops startup and names the variable. */
class RequiredConfigurationTest {

    @Test
    void refusesToStartWithoutTheDatabasePassword() {
        assertThatThrownBy(() -> start(
                "--PREGON_DB_URL=jdbc:postgresql://localhost:1/unused",
                "--PREGON_DB_USER=unused",
                "--PREGON_REDIS_URL=redis://localhost:1"))
                .hasMessage("Missing required configuration: PREGON_DB_PASSWORD."
                        + " Connections and secrets have no defaults (see docs/configuration.md).");
    }

    @Test
    void namesEveryMissingVariableAtOnce() {
        assertThatThrownBy(this::start)
                .hasMessageContaining(String.join(", ", RequiredEnvironment.VARIABLES));
    }

    @Test
    void treatsABlankValueAsMissing() {
        assertThatThrownBy(() -> start(
                "--PREGON_DB_URL=jdbc:postgresql://localhost:1/unused",
                "--PREGON_DB_USER=unused",
                "--PREGON_DB_PASSWORD=",
                "--PREGON_REDIS_URL=redis://localhost:1"))
                .hasMessageContaining("PREGON_DB_PASSWORD");
    }

    private void start(String... args) {
        new SpringApplicationBuilder(PregonServerApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
