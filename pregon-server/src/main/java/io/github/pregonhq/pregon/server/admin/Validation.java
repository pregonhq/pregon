package io.github.pregonhq.pregon.server.admin;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Collects every invalid field of a request, so the client fixes them all in one round trip. */
final class Validation {

    private static final Pattern LOCAL_TIME = Pattern.compile("([01][0-9]|2[0-3]):[0-5][0-9]");
    static final Pattern ROLE = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");

    private final List<ApiProblems.FieldError> errors = new ArrayList<>();

    void add(String pointer, String detail) {
        errors.add(new ApiProblems.FieldError(pointer, detail));
    }

    boolean ok() {
        return errors.isEmpty();
    }

    void throwIfInvalid() {
        if (!errors.isEmpty()) {
            throw new ApiProblems.InvalidRequest(errors);
        }
    }

    String text(String pointer, String value, int max) {
        if (value == null || value.isBlank()) {
            add(pointer, "is required");
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.length() > max) {
            add(pointer, "must have at most " + max + " characters");
            return null;
        }
        return trimmed;
    }

    LocalTime localTime(String pointer, String value) {
        if (value == null || !LOCAL_TIME.matcher(value).matches()) {
            add(pointer, "must be a local time HH:mm");
            return null;
        }
        return LocalTime.parse(value);
    }

    ZoneId timezone(String pointer, String value) {
        if (value == null || !ZoneId.getAvailableZoneIds().contains(value)) {
            add(pointer, "must be an IANA timezone such as America/Mexico_City");
            return null;
        }
        return ZoneId.of(value);
    }

    UUID uuid(String pointer, String value) {
        try {
            return value == null ? missing(pointer) : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            add(pointer, "must be a UUID");
            return null;
        }
    }

    Duration positiveDuration(String pointer, String value) {
        try {
            Duration duration = Duration.parse(value);
            if (duration.isNegative() || duration.isZero()) {
                add(pointer, "must be a positive ISO 8601 duration");
                return null;
            }
            return duration;
        } catch (DateTimeParseException | NullPointerException e) {
            add(pointer, "must be an ISO 8601 duration such as PT10S");
            return null;
        }
    }

    private <T> T missing(String pointer) {
        add(pointer, "is required");
        return null;
    }
}
