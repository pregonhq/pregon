package io.github.pregonhq.pregon.server.stt;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.format.DateTimeParseException;

import io.github.pregonhq.pregon.spi.speech.SpeechToText;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires the sidecar adapter when {@code PREGON_STT_URL} is set.
 *
 * <p>The URL is a connection and has no default (ADR-0020). It is not required either: without a
 * sidecar the server accepts segments and nothing drains the queue (ADR-0025), which is a valid,
 * visible state, not a misconfiguration. The two tunables have documented defaults
 * ({@code docs/configuration.md}).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("PREGON_STT_URL")
class SttConfiguration {

    static final String URL = "PREGON_STT_URL";
    static final String TIMEOUT = "PREGON_STT_TIMEOUT";
    static final String BATCH = "PREGON_STT_BATCH";
    /** A batch of 16 segments of 10 s on a CPU at 1x real time is under 3 min; 5 min leaves room for a cold cache. */
    static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);
    /** The sidecar's own default for {@code PREGON_STT_MAX_BATCH}; the two must agree. */
    static final int DEFAULT_BATCH = 16;
    static final int CONTRACT_MAX_BATCH = 64;

    @Bean
    HttpClient sttHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Bean
    SttProperties sttProperties(Environment environment) {
        return new SttProperties(baseUrl(environment), timeout(environment), batch(environment));
    }

    @Bean
    SpeechToText speechToText(SttProperties properties, HttpClient sttHttpClient) {
        return new SidecarSpeechToText(properties.baseUrl(), properties.timeout(), properties.batch(), sttHttpClient);
    }

    private static Duration timeout(Environment environment) {
        String value = environment.getProperty(TIMEOUT);
        if (value == null || value.isBlank()) {
            return DEFAULT_TIMEOUT;
        }
        Duration timeout;
        try {
            timeout = Duration.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new IllegalStateException(TIMEOUT + " must be an ISO 8601 duration such as PT5M, got '" + value + "'");
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalStateException(TIMEOUT + " must be a positive duration, got " + timeout);
        }
        return timeout;
    }

    private static int batch(Environment environment) {
        String value = environment.getProperty(BATCH);
        if (value == null || value.isBlank()) {
            return DEFAULT_BATCH;
        }
        int batch;
        try {
            batch = Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(BATCH + " must be an integer, got '" + value + "'");
        }
        if (batch < 1 || batch > CONTRACT_MAX_BATCH) {
            throw new IllegalStateException(BATCH + " must be between 1 and " + CONTRACT_MAX_BATCH + " (the sidecar's contract), got " + batch);
        }
        return batch;
    }

    @Bean
    HealthIndicator sttHealthIndicator(SttProperties properties, HttpClient sttHttpClient) {
        return new SttHealthIndicator(properties.baseUrl(), sttHttpClient);
    }

    private static URI baseUrl(Environment environment) {
        String value = environment.getRequiredProperty(URL).strip();
        URI url = URI.create(value.endsWith("/") ? value : value + "/");
        if (url.getScheme() == null || url.getHost() == null) {
            throw new IllegalStateException(URL + " must be an absolute http(s) URL such as http://stt:8000, got '" + value + "'");
        }
        return url;
    }
}
