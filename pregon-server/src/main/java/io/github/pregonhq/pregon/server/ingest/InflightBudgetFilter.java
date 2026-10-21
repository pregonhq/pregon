package io.github.pregonhq.pregon.server.ingest;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Takes the request's bytes from the {@link InflightBudget} before the body is read and gives them
 * back when the request ends, whether it was answered or the agent went away. Runs before
 * authentication, as soon as the headers are parsed, so that an upload holds its budget before any
 * database lookup; a request without an {@code Authorization} header takes nothing, because
 * authentication is about to refuse it unread. Without budget the answer is an immediate
 * {@code 503} with {@code Retry-After}.
 */
final class InflightBudgetFilter extends OncePerRequestFilter {

    private final InflightBudget budget;
    private final long bytesWhenUndeclared;
    private final long retryAfterSeconds;

    InflightBudgetFilter(InflightBudget budget, long bytesWhenUndeclared, long retryAfterSeconds) {
        this.budget = budget;
        this.bytesWhenUndeclared = bytesWhenUndeclared;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"PUT".equals(request.getMethod()) || !request.getRequestURI().startsWith(IngestPrechecksFilter.PATH_PREFIX)
                || request.getHeader("Authorization") == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        // A chunked upload declares nothing: it is charged the most it could carry.
        long bytes = declared >= 0 ? declared : bytesWhenUndeclared;
        if (!budget.tryAcquire(bytes)) {
            IngestProblems.write(response, HttpStatus.SERVICE_UNAVAILABLE,
                    "The server is holding as many uploads as it can right now. Retry after the indicated delay.", retryAfterSeconds);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            budget.release(bytes);
        }
    }
}
