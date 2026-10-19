package io.github.pregonhq.pregon.spi.speech;

import java.time.Duration;
import java.util.Objects;

/**
 * A stretch of transcript with its timing and the provider's confidence figures.
 *
 * <p>The three figures are how the server tells speech from hallucination (ADR-0001: drop early,
 * drop cheaply). Whisper reports all three; a provider that lacks one reports {@link Double#NaN},
 * never a made-up value.
 *
 * @param start offset from the beginning of the audio
 * @param end offset from the beginning of the audio, not before {@code start}
 * @param text the words in this span
 * @param avgLogprob average log-probability of the tokens; very negative means low confidence
 * @param noSpeechProb probability (0 to 1) that the span is not speech; high values flag invented text
 * @param compressionRatio gzip ratio of the text; above about 2.4 the model is repeating itself
 */
public record SpeechSpan(Duration start, Duration end, String text, double avgLogprob, double noSpeechProb,
        double compressionRatio) {

    public SpeechSpan {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(text, "text");
        if (start.isNegative() || end.compareTo(start) < 0) {
            throw new IllegalArgumentException("span must satisfy 0 <= start <= end, got " + start + " to " + end);
        }
        if (!Double.isNaN(noSpeechProb) && (noSpeechProb < 0.0 || noSpeechProb > 1.0)) {
            throw new IllegalArgumentException("noSpeechProb must be between 0 and 1, got " + noSpeechProb);
        }
    }

    /** Whether the provider reported the three confidence figures. */
    public boolean hasConfidence() {
        return !Double.isNaN(avgLogprob) && !Double.isNaN(noSpeechProb) && !Double.isNaN(compressionRatio);
    }
}
