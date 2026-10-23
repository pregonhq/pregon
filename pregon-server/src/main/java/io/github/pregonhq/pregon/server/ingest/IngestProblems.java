package io.github.pregonhq.pregon.server.ingest;

import java.io.IOException;
import java.io.Serial;
import java.util.List;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The failures of {@code PUT /v1/segments/{segmentId}} as the RFC 9457 responses the contract
 * declares. Agents read the status, never the text: a 4xx is never retried, a 503 is retried
 * after {@code Retry-After}.
 */
@RestControllerAdvice(assignableTypes = SegmentController.class)
class IngestProblems {

    /** One invalid field: a JSON Pointer into the metadata part and what is wrong. */
    record FieldError(String pointer, String detail) {
    }

    static final class InvalidRequest extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        private final transient List<FieldError> errors;

        InvalidRequest(String detail) {
            this(detail, List.of());
        }

        InvalidRequest(String detail, List<FieldError> errors) {
            super(detail);
            this.errors = List.copyOf(errors);
        }
    }

    /** The source does not exist in the caller's tenant; another tenant's source looks exactly like this. */
    static final class SourceNotFound extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        SourceNotFound() {
            super("No such resource in the caller's tenant.");
        }
    }

    /** Same id, other content (an agent bug) or an archived source: in both cases the agent must stop. */
    static final class Conflict extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        Conflict(String detail) {
            super(detail);
        }
    }

    static final class TooLarge extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        TooLarge(String detail) {
            super(detail);
        }
    }

    /** The queue has no room for this segment yet; the agent keeps it and comes back. */
    static final class Throttled extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        private final long retryAfterSeconds;

        Throttled(String detail, long retryAfterSeconds) {
            super(detail);
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    @ExceptionHandler
    ProblemDetail invalid(InvalidRequest exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        if (!exception.errors.isEmpty()) {
            problem.setProperty("errors", exception.errors);
        }
        return problem;
    }

    @ExceptionHandler
    ProblemDetail notFound(SourceNotFound exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler
    ProblemDetail conflict(Conflict exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler
    ProblemDetail tooLarge(TooLarge exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, exception.getMessage());
    }

    @ExceptionHandler
    ResponseEntity<ProblemDetail> throttled(Throttled exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds))
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage()));
    }

    /** For the filters, which answer before Spring MVC exists for the request. */
    static void write(HttpServletResponse response, HttpStatus status, String detail, Long retryAfterSeconds) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        if (retryAfterSeconds != null) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        }
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase() + "\",\"status\":"
                + status.value() + ",\"detail\":\"" + detail.replace("\"", "'") + "\"}");
    }
}
