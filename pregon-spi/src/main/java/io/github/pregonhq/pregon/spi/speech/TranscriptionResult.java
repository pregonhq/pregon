package io.github.pregonhq.pregon.spi.speech;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The outcome for one segment of a batch: either its transcript or the reason it has none.
 *
 * <p>A transcript may be empty: the provider found no speech worth writing. That is a result,
 * not a failure.
 */
public sealed interface TranscriptionResult {

    UUID segmentId();

    /**
     * @param text the whole transcript; may be empty
     * @param language the language the provider used
     * @param audioDuration decoded length of the audio
     * @param spans the provider's own split of the transcript, with timing and confidence; may be empty
     * @param processingTime wall-clock time the provider spent on this segment
     */
    record Transcribed(UUID segmentId, String text, String language, Duration audioDuration, List<SpeechSpan> spans,
            Duration processingTime) implements TranscriptionResult {

        public Transcribed {
            Objects.requireNonNull(segmentId, "segmentId");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(language, "language");
            Objects.requireNonNull(audioDuration, "audioDuration");
            Objects.requireNonNull(processingTime, "processingTime");
            spans = spans == null ? List.of() : List.copyOf(spans);
        }
    }

    /**
     * @param reason why there is no transcript, e.g. the audio could not be decoded; never
     *        contains audio or text
     */
    record Failed(UUID segmentId, String reason) implements TranscriptionResult {

        public Failed {
            Objects.requireNonNull(segmentId, "segmentId");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
