package io.github.pregonhq.pregon.spi.speech;

import java.util.List;

/**
 * Turns audio segments into text, several at a time.
 *
 * <p>A batch either succeeds as a whole, with one {@link TranscriptionResult} per segment in the
 * same order as the request, or fails as a whole with a {@link SpeechToTextException} that says
 * whether trying again later makes sense. One segment whose audio cannot be processed does not
 * fail the batch: it comes back as {@link TranscriptionResult.Failed}.
 *
 * <p>Implementations never keep the audio after the call returns (ADR-0006).
 */
public interface SpeechToText {

    /** The most segments one call accepts; callers split larger batches. At least 1. */
    int maxBatchSize();

    /**
     * Transcribes every segment of the request.
     *
     * @throws IllegalArgumentException when the request carries more segments than
     *         {@link #maxBatchSize()}
     * @throws SpeechToTextException when the provider could not process the batch at all
     */
    List<TranscriptionResult> transcribe(TranscriptionRequest request);
}
