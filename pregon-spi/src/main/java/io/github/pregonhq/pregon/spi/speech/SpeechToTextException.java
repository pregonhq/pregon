package io.github.pregonhq.pregon.spi.speech;

import java.time.Duration;
import java.util.Optional;

/**
 * The provider could not process a batch at all.
 *
 * <p>{@link #isRetryable()} tells the caller what to do: a provider that is busy, loading or
 * unreachable will take the same batch later, while a batch the provider rejected as malformed
 * would be rejected again, which points at a bug on the calling side. The message never contains
 * audio or transcript text.
 */
public class SpeechToTextException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean retryable;
    private final Duration retryAfter;

    public SpeechToTextException(String message, boolean retryable, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.retryAfter = retryAfter;
    }

    /** A failure worth retrying, with the delay the provider asked for when it gave one. */
    public static SpeechToTextException retryable(String message, Duration retryAfter, Throwable cause) {
        return new SpeechToTextException(message, true, retryAfter, cause);
    }

    /** A failure that would repeat with the same batch. */
    public static SpeechToTextException permanent(String message) {
        return new SpeechToTextException(message, false, null, null);
    }

    public boolean isRetryable() {
        return retryable;
    }

    /** How long the provider asked the caller to wait; only ever present when retryable. */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
