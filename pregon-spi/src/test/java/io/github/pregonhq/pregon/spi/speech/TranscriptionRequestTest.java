package io.github.pregonhq.pregon.spi.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** The port's value types reject what no provider could process, before any network call. */
class TranscriptionRequestTest {

    private static final AudioSegment SEGMENT = new AudioSegment(UUID.randomUUID(), new byte[] {1, 2, 3});

    @Test
    void acceptsALanguageSubtagAndNormalisesAMissingPrompt() {
        TranscriptionRequest request = new TranscriptionRequest("es", null, List.of(SEGMENT));

        assertEquals("", request.prompt());
        assertEquals("", new TranscriptionRequest("es", List.of(SEGMENT)).prompt());
        assertEquals(List.of(SEGMENT), request.segments());
    }

    @Test
    void rejectsAnythingThatIsNotAPrimaryLanguageSubtag() {
        for (String language : new String[] {"", "ES", "es-MX", "spanish", "e"}) {
            assertThrows(IllegalArgumentException.class, () -> new TranscriptionRequest(language, List.of(SEGMENT)), language);
        }
    }

    @Test
    void rejectsAnEmptyBatchAndRepeatedIds() {
        assertThrows(IllegalArgumentException.class, () -> new TranscriptionRequest("es", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TranscriptionRequest("es", List.of(SEGMENT, SEGMENT)));
    }

    @Test
    void aSegmentNeedsAudio() {
        assertThrows(IllegalArgumentException.class, () -> new AudioSegment(UUID.randomUUID(), new byte[0]));
    }

    @Test
    void aSpanIsOrderedAndItsConfidenceIsOptional() {
        SpeechSpan reported = new SpeechSpan(Duration.ZERO, Duration.ofSeconds(2), "hola", -0.3, 0.1, 1.2);
        SpeechSpan unreported = new SpeechSpan(Duration.ZERO, Duration.ofSeconds(2), "hola", Double.NaN, Double.NaN, Double.NaN);

        assertTrue(reported.hasConfidence());
        assertFalse(unreported.hasConfidence());
        assertThrows(IllegalArgumentException.class,
                () -> new SpeechSpan(Duration.ofSeconds(3), Duration.ofSeconds(2), "hola", -0.3, 0.1, 1.2));
        assertThrows(IllegalArgumentException.class,
                () -> new SpeechSpan(Duration.ZERO, Duration.ofSeconds(2), "hola", -0.3, 1.5, 1.2));
    }

    @Test
    void aFailureSaysWhetherToRetry() {
        SpeechToTextException busy = SpeechToTextException.retryable("sidecar is loading", Duration.ofSeconds(5), null);
        SpeechToTextException bug = SpeechToTextException.permanent("sidecar rejected the request");

        assertTrue(busy.isRetryable());
        assertEquals(Duration.ofSeconds(5), busy.retryAfter().orElseThrow());
        assertFalse(bug.isRetryable());
        assertTrue(bug.retryAfter().isEmpty());
    }
}
