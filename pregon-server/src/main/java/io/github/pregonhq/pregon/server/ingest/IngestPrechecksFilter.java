package io.github.pregonhq.pregon.server.ingest;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The cheapest rejections of ADR-0025 §1, taken from the request line and headers alone, before
 * authentication and before a single byte of the body is read: a declared body too large for any
 * accepted segment is {@code 413}, a segment id that is not a UUID is {@code 400}.
 */
final class IngestPrechecksFilter extends OncePerRequestFilter {

    static final String PATH_PREFIX = "/v1/segments/";

    private final long maxBodyBytes;

    IngestPrechecksFilter(long maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"PUT".equals(request.getMethod()) || !request.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBodyBytes) {
            IngestProblems.write(response, HttpStatus.CONTENT_TOO_LARGE,
                    "The body is larger than any accepted segment: at most " + maxBodyBytes + " bytes including the metadata.", null);
            return;
        }
        if (segmentId(request) == null) {
            IngestProblems.write(response, HttpStatus.BAD_REQUEST, "The segment id must be a UUID.", null);
            return;
        }
        chain.doFilter(request, response);
    }

    /** The id in the path, or null when there is none or it is not a UUID. */
    static UUID segmentId(HttpServletRequest request) {
        String rest = request.getRequestURI().substring(PATH_PREFIX.length());
        if (rest.isEmpty() || rest.contains("/")) {
            return null;
        }
        try {
            return UUID.fromString(rest);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
