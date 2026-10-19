package io.github.pregonhq.pregon.server.stt;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reports the sidecar's own {@code GET /health} as the {@code stt} component of the server's health.
 *
 * <p>Shown in {@code /actuator/health}, deliberately absent from the readiness group: a sidecar
 * that is loading its model or down must not take the server out of rotation, because ingestion
 * keeps accepting segments and the queue absorbs the gap (ADR-0024, ADR-0025).
 */
public final class SttHealthIndicator implements HealthIndicator {

    static final String HEALTH_PATH = "/health";
    static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final URI health;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    public SttHealthIndicator(URI baseUrl, HttpClient http) {
        this.health = baseUrl.resolve(HEALTH_PATH);
        this.http = http;
    }

    @Override
    public Health health() {
        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(health).timeout(TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Health.down().withDetail("error", "unreachable: " + e.getMessage()).withDetail("url", health.toString()).build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Health.down().withDetail("error", "interrupted").build();
        }
        Map<String, Object> body = body(response.body());
        Health.Builder result = response.statusCode() == 200 && "UP".equals(body.get("status")) ? Health.up() : Health.down();
        for (String key : new String[] {"model", "device", "computeType", "detail"}) {
            if (body.get(key) != null) {
                result.withDetail(key, body.get(key));
            }
        }
        if (response.statusCode() != 200) {
            result.withDetail("httpStatus", response.statusCode());
        }
        return result.build();
    }

    private Map<String, Object> body(String text) {
        try {
            Map<String, Object> parsed = json.readValue(text, json.constructType(Map.class));
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException notJson) {
            return Map.of();
        }
    }
}
