package io.github.pregonhq.pregon.server;

import java.util.List;

import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Stops startup when a connection or a secret is missing (ADR-0020).
 *
 * <p>Needed because Spring Boot does not fail on its own: when it binds a property such as
 * {@code spring.datasource.password: ${PREGON_DB_PASSWORD}} and the variable is not set, it keeps
 * the placeholder text as the value. The server would then start and fail later, far from the
 * cause. Only {@code @Value} rejects unresolvable placeholders.
 *
 * <p>Runs when the context is prepared: every property source is in place (including those added
 * by tests) and no bean, and therefore no connection, has been created yet.
 */
class RequiredEnvironment implements ApplicationListener<ApplicationPreparedEvent> {

    static final List<String> VARIABLES = List.of(
            "PREGON_DB_URL",
            "PREGON_DB_USER",
            "PREGON_DB_PASSWORD",
            "PREGON_REDIS_URL");

    @Override
    public void onApplicationEvent(ApplicationPreparedEvent event) {
        Environment environment = event.getApplicationContext().getEnvironment();
        List<String> missing = VARIABLES.stream()
                .filter(name -> !StringUtils.hasText(environment.getProperty(name)))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing required configuration: " + String.join(", ", missing)
                    + ". Connections and secrets have no defaults (see docs/configuration.md).");
        }
    }
}
