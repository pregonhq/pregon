package io.github.pregonhq.pregon.server.transcription;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.core.env.Environment;

/**
 * The tunables of the transcription worker and of transcript retention (ADR-0020: documented
 * defaults, each with its reason in {@code docs/configuration.md}), read once at startup and
 * refused with the variable's name when they make no sense.
 *
 * @param language the installation's one audio language (ADR-0008), sent with every batch
 * @param lease how long a claimed job is owned; must outlive the sidecar timeout (ADR-0027)
 * @param poll how long the worker waits when the queue is empty before looking again
 * @param transcriptRetention how long transcripts are kept; empty means they are never stored
 */
record TranscriptionProperties(String language, Duration lease, Duration poll, Optional<Duration> transcriptRetention) {

    static final String LANGUAGE = "PREGON_LANGUAGE";
    static final String LEASE = "PREGON_STT_LEASE";
    static final String POLL = "PREGON_STT_POLL";
    static final String RETENTION_TRANSCRIPTS = "PREGON_RETENTION_TRANSCRIPTS";
    static final String RETENTION_AUDIO = "PREGON_RETENTION_AUDIO";

    static final String DEFAULT_LANGUAGE = "es";
    /** The sidecar timeout (PT5M) plus the time to claim, store and report; see docs/capacity.md. */
    static final Duration DEFAULT_LEASE = Duration.ofMinutes(6);
    /** Latency a 10 s segment can afford while waiting for an empty queue to fill; LISTEN/NOTIFY is ADR-0025's step 2. */
    static final Duration DEFAULT_POLL = Duration.ofSeconds(2);
    static final String RETENTION_NONE = "none";
    /** The only audio retention implemented: the bytes go when the segment is transcribed or fails (ADR-0006). */
    static final String RETENTION_AUDIO_DEFAULT = "delete-after-transcription";

    private static final Pattern LANGUAGE_CODE = Pattern.compile("[a-z]{2,3}");

    /**
     * Reads the variables; {@code sttTimeout} is the sidecar client's timeout, which the lease must
     * exceed so that a job still leased by a live worker is never handed to a second one.
     */
    static TranscriptionProperties from(Environment env, Duration sttTimeout) {
        TranscriptionProperties properties = new TranscriptionProperties(
                language(env),
                duration(env, LEASE, DEFAULT_LEASE),
                duration(env, POLL, DEFAULT_POLL),
                transcriptRetention(env));
        if (properties.lease().compareTo(sttTimeout) <= 0) {
            throw new IllegalStateException(LEASE + " must be longer than PREGON_STT_TIMEOUT (" + sttTimeout
                    + "), got " + properties.lease() + ": a batch still in flight would lose its lease");
        }
        String audio = env.getProperty(RETENTION_AUDIO);
        if (audio != null && !audio.isBlank() && !RETENTION_AUDIO_DEFAULT.equals(audio.strip())) {
            throw new IllegalStateException(RETENTION_AUDIO + " only supports '" + RETENTION_AUDIO_DEFAULT + "' today, got '" + audio + "'");
        }
        return properties;
    }

    private static String language(Environment env) {
        String value = env.getProperty(LANGUAGE);
        if (value == null || value.isBlank()) {
            return DEFAULT_LANGUAGE;
        }
        String code = value.strip();
        if (!LANGUAGE_CODE.matcher(code).matches()) {
            throw new IllegalStateException(LANGUAGE + " must be an ISO 639 code such as es, got '" + value + "'");
        }
        return code;
    }

    /** Readable on its own: the purge runs in every server, with or without a sidecar. */
    static Optional<Duration> transcriptRetention(Environment env) {
        String value = env.getProperty(RETENTION_TRANSCRIPTS);
        if (value == null || value.isBlank() || RETENTION_NONE.equals(value.strip())) {
            return Optional.empty();
        }
        return Optional.of(duration(env, RETENTION_TRANSCRIPTS, Duration.ofDays(30)));
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
}
