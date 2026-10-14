package io.github.pregonhq.pregon.server.security;

import java.io.IOException;
import java.util.List;

import io.github.pregonhq.pregon.core.apikey.ApiKeyAuthenticator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads {@code Authorization: Bearer <key>} and, when the key is valid, authenticates the request
 * with one authority per scope. Without the header the request continues anonymous, and the
 * authorization rules decide; with a header that does not authenticate, it stops here with 401.
 */
class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final ApiKeyAuthenticator authenticator;
    private final AuthenticationEntryPoint entryPoint;

    ApiKeyAuthenticationFilter(ApiKeyAuthenticator authenticator, AuthenticationEntryPoint entryPoint) {
        this.authenticator = authenticator;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            chain.doFilter(request, response);
            return;
        }
        String key = header.startsWith(BEARER) ? header.substring(BEARER.length()) : null;
        if (authenticator.authenticate(key) instanceof ApiKeyAuthenticator.Result.Authenticated authenticated) {
            var authorities = authenticated.scopes().stream()
                    .map(scope -> new SimpleGrantedAuthority(scope.name()))
                    .toList();
            var authentication = UsernamePasswordAuthenticationToken.authenticated(
                    new ApiKeyPrincipal(authenticated.keyId(), authenticated.tenantId()), null, List.copyOf(authorities));
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            try {
                chain.doFilter(request, response);
            } finally {
                SecurityContextHolder.clearContext();
            }
            return;
        }
        SecurityContextHolder.clearContext();
        entryPoint.commence(request, response, new BadCredentialsException("Invalid API key"));
    }
}
