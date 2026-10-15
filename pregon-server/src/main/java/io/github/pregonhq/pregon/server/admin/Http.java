package io.github.pregonhq.pregon.server.admin;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import io.github.pregonhq.pregon.server.security.ApiKeyPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;

/** Small HTTP conventions shared by the Admin API: tenant, ETags, cursors and paging limits. */
final class Http {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private Http() {
    }

    /** The tenant comes from the API key, never from the request (ADR-0018). */
    static String tenantId() {
        return ((ApiKeyPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).tenantId();
    }

    static String etag(long version) {
        return "\"" + version + "\"";
    }

    /** The version an If-Match header asks for; missing is 428, anything unparseable can never match. */
    static long expectedVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiProblems.PreconditionRequired();
        }
        String value = ifMatch.strip();
        if (value.length() < 3 || !value.startsWith("\"") || !value.endsWith("\"")) {
            throw new ApiProblems.PreconditionFailed();
        }
        try {
            return Long.parseLong(value.substring(1, value.length() - 1));
        } catch (NumberFormatException e) {
            throw new ApiProblems.PreconditionFailed();
        }
    }

    static int limit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ApiProblems.InvalidRequest(java.util.List.of(
                    new ApiProblems.FieldError("limit", "must be between 1 and " + MAX_LIMIT)));
        }
        return limit;
    }

    /** Opaque to clients: the id of the last item of the previous page. */
    static String cursor(UUID lastId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(lastId.toString().getBytes(StandardCharsets.US_ASCII));
    }

    static UUID afterCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            return UUID.fromString(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException e) {
            throw new ApiProblems.InvalidRequest(java.util.List.of(new ApiProblems.FieldError("cursor", "is not a cursor from this API")));
        }
    }

    static String status(String status) {
        if (status == null) {
            return "ACTIVE";
        }
        if (!status.equals("ACTIVE") && !status.equals("ARCHIVED")) {
            throw new ApiProblems.InvalidRequest(java.util.List.of(new ApiProblems.FieldError("status", "must be ACTIVE or ARCHIVED")));
        }
        return status;
    }
}
