package io.github.pregonhq.pregon.server.ingest;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code metadata} part of an upload, parsed and validated (contract: {@code SegmentMetadata}).
 * Every invalid field is reported at once. Values are kept parsed, so comparing two uploads of the
 * same id compares by value (ADR-0025 §5), and {@code agentVersion} is diagnostic only.
 */
record SegmentMetadata(UUID sourceId, Instant captureStart, Instant captureEnd, Duration voicedDuration, String agentVersion) {

    /** The JSON as agents send it; unknown fields are refused by the mapper (a misspelled field is an agent bug). */
    record Json(String sourceId, String captureStart, String captureEnd, String voicedDuration, String agentVersion) {
    }

    static SegmentMetadata parse(String json, JsonMapper mapper) {
        Json raw;
        try {
            raw = mapper.readValue(json, Json.class);
        } catch (RuntimeException e) {
            throw new IngestProblems.InvalidRequest("The metadata part is not the JSON object the contract describes.");
        }
        if (raw == null) {
            throw new IngestProblems.InvalidRequest("The metadata part is empty.");
        }
        List<IngestProblems.FieldError> errors = new ArrayList<>();
        UUID sourceId = null;
        if (raw.sourceId() == null) {
            errors.add(new IngestProblems.FieldError("/sourceId", "is required"));
        } else {
            try {
                sourceId = UUID.fromString(raw.sourceId());
            } catch (IllegalArgumentException e) {
                errors.add(new IngestProblems.FieldError("/sourceId", "must be a UUID"));
            }
        }
        Instant start = instant("/captureStart", raw.captureStart(), errors);
        Instant end = instant("/captureEnd", raw.captureEnd(), errors);
        if (start != null && end != null && !end.isAfter(start)) {
            errors.add(new IngestProblems.FieldError("/captureEnd", "must be after captureStart"));
        }
        Duration voiced = null;
        if (raw.voicedDuration() == null) {
            errors.add(new IngestProblems.FieldError("/voicedDuration", "is required"));
        } else {
            try {
                voiced = Duration.parse(raw.voicedDuration());
                if (voiced.isNegative() || voiced.isZero()) {
                    errors.add(new IngestProblems.FieldError("/voicedDuration", "must be a positive ISO 8601 duration"));
                    voiced = null;
                } else if (start != null && end != null && voiced.compareTo(Duration.between(start, end)) > 0) {
                    errors.add(new IngestProblems.FieldError("/voicedDuration", "cannot be longer than the segment"));
                }
            } catch (DateTimeParseException e) {
                errors.add(new IngestProblems.FieldError("/voicedDuration", "must be an ISO 8601 duration such as PT6.4S"));
            }
        }
        if (raw.agentVersion() != null && raw.agentVersion().length() > 40) {
            errors.add(new IngestProblems.FieldError("/agentVersion", "must have at most 40 characters"));
        }
        if (!errors.isEmpty()) {
            throw new IngestProblems.InvalidRequest("The metadata has invalid fields.", errors);
        }
        return new SegmentMetadata(sourceId, start, end, voiced, raw.agentVersion());
    }

    private static Instant instant(String pointer, String value, List<IngestProblems.FieldError> errors) {
        if (value == null) {
            errors.add(new IngestProblems.FieldError(pointer, "is required"));
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            errors.add(new IngestProblems.FieldError(pointer, "must be an RFC 3339 date-time"));
            return null;
        }
    }

    long voicedMillis() {
        return voicedDuration.toMillis();
    }
}
