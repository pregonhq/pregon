package io.github.pregonhq.pregon.server.security;

import io.github.pregonhq.pregon.core.apikey.ApiKeyAuthenticator;
import io.github.pregonhq.pregon.core.apikey.ApiKeyScope;
import io.github.pregonhq.pregon.server.apikey.ApiKeys;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * API key authentication for the Ingest and Admin APIs (ADR-0003, ADR-0018).
 *
 * <p>Stateless: no sessions, no cookies, so CSRF protection does not apply. Anything not listed
 * here is denied, so a new endpoint is closed until someone decides its scope. Not loaded by the
 * {@code pregon keys} command, which runs without a web server.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
class SecurityConfiguration {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ApiKeys apiKeys) throws Exception {
        var unauthorized = ProblemResponses.unauthorized();
        var apiKeyFilter = new ApiKeyAuthenticationFilter(new ApiKeyAuthenticator(apiKeys), unauthorized);
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(apiKeyFilter, AuthorizationFilter.class)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(ProblemResponses.forbidden()))
                .authorizeHttpRequests(requests -> requests
                        // The container re-dispatches errors (e.g. 404) to /error without the
                        // original authentication; denying it would turn every error into a 401.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.PUT, "/v1/segments/*").hasAuthority(ApiKeyScope.INGEST.name())
                        .requestMatchers(HttpMethod.GET, "/v1/segments/*").hasAuthority(ApiKeyScope.ADMIN.name())
                        .requestMatchers("/v1/segments/**").denyAll()
                        .requestMatchers("/v1/**").hasAuthority(ApiKeyScope.ADMIN.name())
                        .anyRequest().denyAll())
                .build();
    }
}
