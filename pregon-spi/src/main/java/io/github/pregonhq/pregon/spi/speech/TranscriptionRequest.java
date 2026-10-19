package io.github.pregonhq.pregon.spi.speech;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A batch of segments to transcribe in one language.
 *
 * @param language BCP 47 primary language subtag, e.g. {@code es}; fixed per installation (ADR-0008)
 * @param prompt text the model should expect, such as campaign phrases and product names, so
 *        that they are transcribed as written; empty when there is none
 * @param segments at least one, with distinct ids
 */
public record TranscriptionRequest(String language, String prompt, List<AudioSegment> segments) {

    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}");

    public TranscriptionRequest {
        Objects.requireNonNull(language, "language");
        if (!LANGUAGE.matcher(language).matches()) {
            throw new IllegalArgumentException("language must be a BCP 47 primary subtag such as 'es', not '" + language + "'");
        }
        prompt = prompt == null ? "" : prompt;
        Objects.requireNonNull(segments, "segments");
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("a request needs at least one segment");
        }
        Set<UUID> ids = new HashSet<>();
        for (AudioSegment segment : segments) {
            Objects.requireNonNull(segment, "segment");
            if (!ids.add(segment.id())) {
                throw new IllegalArgumentException("segment " + segment.id() + " appears twice");
            }
        }
        segments = List.copyOf(segments);
    }

    public TranscriptionRequest(String language, List<AudioSegment> segments) {
        this(language, "", segments);
    }
}
