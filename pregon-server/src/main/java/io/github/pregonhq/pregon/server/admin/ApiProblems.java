package io.github.pregonhq.pregon.server.admin;

import java.io.Serial;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps Admin API failures to the RFC 9457 responses declared in the contract. */
@RestControllerAdvice
class ApiProblems {

    /** One invalid field: a JSON Pointer (RFC 6901) into the request body and what is wrong. */
    record FieldError(String pointer, String detail) {
    }

    static final class InvalidRequest extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        private final transient List<FieldError> errors;

        InvalidRequest(List<FieldError> errors) {
            super("Invalid request");
            this.errors = List.copyOf(errors);
        }
    }

    static final class NotFound extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        NotFound() {
            super("No such resource in the caller's tenant.");
        }
    }

    /** The resource, or one it refers to, is archived. */
    static final class Archived extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        Archived(String detail) {
            super(detail);
        }
    }

    static final class Conflict extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        Conflict(String detail) {
            super(detail);
        }
    }

    static final class PreconditionFailed extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        PreconditionFailed() {
            super("If-Match does not match the current version: someone else changed the resource. Read it again and retry.");
        }
    }

    static final class PreconditionRequired extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        PreconditionRequired() {
            super("Send If-Match with the ETag read before replacing the resource.");
        }
    }

    @ExceptionHandler
    ProblemDetail invalid(InvalidRequest exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request has invalid fields.");
        problem.setProperty("errors", exception.errors);
        return problem;
    }

    @ExceptionHandler
    ProblemDetail notFound(NotFound exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler
    ProblemDetail archived(Archived exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler
    ProblemDetail conflict(Conflict exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler
    ProblemDetail preconditionFailed(PreconditionFailed exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PRECONDITION_FAILED, exception.getMessage());
    }

    @ExceptionHandler
    ProblemDetail preconditionRequired(PreconditionRequired exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PRECONDITION_REQUIRED, exception.getMessage());
    }
}
