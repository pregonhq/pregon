package io.github.pregonhq.pregon.spi.speech;

import java.util.Objects;
import java.util.UUID;

/**
 * One speech segment to transcribe: Opus in an Ogg container, as agents produce it (ADR-0007).
 *
 * <p>The array is not copied: audio is kept in memory only as long as needed (ADR-0006), and a
 * copy per segment would double that. Callers must not modify it after handing it over.
 *
 * @param id the segment id the agent generated (ADR-0022)
 * @param audio the Ogg/Opus bytes, at least one
 */
public record AudioSegment(UUID id, byte[] audio) {

    public AudioSegment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(audio, "audio");
        if (audio.length == 0) {
            throw new IllegalArgumentException("segment " + id + " has no audio");
        }
    }
}
