package io.github.pregonhq.pregon.server.ingest;

import java.time.Duration;
import java.time.Period;
import java.time.format.DateTimeParseException;

import org.springframework.core.env.Environment;

/**
 * The tunables of ingestion and the queue (ADR-0020: documented defaults, each with its reason in
 * {@code docs/configuration.md}), read once at startup and refused with the variable's name when
 * they make no sense.
 */
record IngestProperties(
        int maxSegmentBytes,
        long queueMaxBytes,
        double queueSourceShare,
        long inflightMaxBytes,
        Duration retryAfterMin,
        Duration retryAfterMax,
        Duration clockSkewThreshold,
        int maxAttempts,
        Period retentionSegments) {

    static final String MAX_SEGMENT_BYTES = "PREGON_INGEST_MAX_SEGMENT_BYTES";
    static final String QUEUE_MAX_BYTES = "PREGON_INGEST_QUEUE_MAX_BYTES";
    static final String QUEUE_SOURCE_SHARE = "PREGON_INGEST_QUEUE_SOURCE_SHARE";
    static final String INFLIGHT_MAX_BYTES = "PREGON_INGEST_INFLIGHT_MAX_BYTES";
    static final String RETRY_AFTER_MIN = "PREGON_INGEST_RETRY_AFTER_MIN";
    static final String RETRY_AFTER_MAX = "PREGON_INGEST_RETRY_AFTER_MAX";
    static final String CLOCK_SKEW_THRESHOLD = "PREGON_INGEST_CLOCK_SKEW_THRESHOLD";
    static final String MAX_ATTEMPTS = "PREGON_STT_MAX_ATTEMPTS";
    static final String RETENTION_SEGMENTS = "PREGON_RETENTION_SEGMENTS";

    /** Room for the metadata part and the multipart framing on top of the largest audio. */
    static final int MULTIPART_OVERHEAD = 64 * 1024;

    static IngestProperties from(Environment env) {
        IngestProperties properties = new IngestProperties(
                (int) longValue(env, MAX_SEGMENT_BYTES, 1_048_576, 1, Integer.MAX_VALUE - MULTIPART_OVERHEAD),
                longValue(env, QUEUE_MAX_BYTES, 268_435_456, 1, Long.MAX_VALUE),
                share(env),
                longValue(env, INFLIGHT_MAX_BYTES, 67_108_864, 1, Long.MAX_VALUE),
                duration(env, RETRY_AFTER_MIN, Duration.ofSeconds(5)),
                duration(env, RETRY_AFTER_MAX, Duration.ofMinutes(5)),
                duration(env, CLOCK_SKEW_THRESHOLD, Duration.ofMinutes(2)),
                (int) longValue(env, MAX_ATTEMPTS, 3, 1, 100),
                period(env, RETENTION_SEGMENTS, Period.ofMonths(1)));
        if (properties.retryAfterMax().compareTo(properties.retryAfterMin()) < 0) {
            throw new IllegalStateException(RETRY_AFTER_MAX + " must not be shorter than " + RETRY_AFTER_MIN);
        }
        return properties;
    }

    /** The largest request body that can carry an accepted segment; anything over it is refused unread. */
    long maxBodyBytes() {
        return (long) maxSegmentBytes + MULTIPART_OVERHEAD;
    }

    long sourceQuotaBytes() {
        return (long) Math.floor(queueMaxBytes * queueSourceShare);
    }

    private static long longValue(Environment env, String name, long fallback, long min, long max) {
        String value = env.getProperty(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        long parsed;
        try {
            parsed = Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(name + " must be an integer, got '" + value + "'");
        }
        if (parsed < min || parsed > max) {
            throw new IllegalStateException(name + " must be between " + min + " and " + max + ", got " + parsed);
        }
        return parsed;
    }

    private static double share(Environment env) {
        String value = env.getProperty(QUEUE_SOURCE_SHARE);
        if (value == null || value.isBlank()) {
            return 0.5;
        }
        double parsed;
        try {
            parsed = Double.parseDouble(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(QUEUE_SOURCE_SHARE + " must be a number between 0 and 1, got '" + value + "'");
        }
        if (!(parsed > 0.0 && parsed <= 1.0)) {
            throw new IllegalStateException(QUEUE_SOURCE_SHARE + " must be between 0 (exclusive) and 1, got " + parsed);
        }
        return parsed;
    }

    private static Duration duration(Environment env, String name, Duration fallback) {
        String value = env.getProperty(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        Duration parsed;
        try {
            parsed = Duration.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new IllegalStateException(name + " must be an ISO 8601 duration such as " + fallback + ", got '" + value + "'");
        }
        if (parsed.isNegative() || parsed.isZero()) {
            throw new IllegalStateException(name + " must be a positive duration, got " + parsed);
        }
        return parsed;
    }

    private static Period period(Environment env, String name, Period fallback) {
        String value = env.getProperty(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        Period parsed;
        try {
            parsed = Period.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new IllegalStateException(name + " must be an ISO 8601 period such as " + fallback + ", got '" + value + "'");
        }
        if (parsed.isNegative() || parsed.isZero()) {
            throw new IllegalStateException(name + " must be a positive period, got " + parsed);
        }
        return parsed;
    }
}
