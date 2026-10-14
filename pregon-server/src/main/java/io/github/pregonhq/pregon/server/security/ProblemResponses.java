package io.github.pregonhq.pregon.server.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * 401 and 403 as RFC 9457 Problem Details, as the contract requires. Messages are identical for
 * every cause on purpose, so they reveal nothing about which keys exist.
 */
final class ProblemResponses {

    private ProblemResponses() {
    }

    static AuthenticationEntryPoint unauthorized() {
        return (request, response, exception) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            write(response, HttpStatus.UNAUTHORIZED, "Missing, malformed, unknown or revoked API key.");
        };
    }

    static AccessDeniedHandler forbidden() {
        return (request, response, exception) ->
                write(response, HttpStatus.FORBIDDEN, "The API key does not have the scope this operation requires.");
    }

    private static void write(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase()
                + "\",\"status\":" + status.value() + ",\"detail\":\"" + detail + "\"}");
    }
}
