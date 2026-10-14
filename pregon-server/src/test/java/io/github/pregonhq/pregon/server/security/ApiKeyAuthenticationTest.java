package io.github.pregonhq.pregon.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;

import io.github.pregonhq.pregon.core.apikey.ApiKeyScope;
import io.github.pregonhq.pregon.core.apikey.ApiKeyToken;
import io.github.pregonhq.pregon.server.InfrastructureContainers;
import io.github.pregonhq.pregon.server.apikey.ApiKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * ADR-0018 against a real PostgreSQL. The Admin and Ingest endpoints do not exist yet, so a
 * request that passes security ends in 404; what matters here is that it is neither 401 nor 403.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiKeyAuthenticationTest {

    private static final String SEGMENT = "/v1/segments/0199e7a0-0000-7000-8000-000000000001";

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        InfrastructureContainers.register(registry);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApiKeys apiKeys;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void healthNeedsNoKey() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void anAdminKeyPassesOnTheAdminApi() throws Exception {
        ApiKeyToken admin = apiKeys.create("default", "admin test", Set.of(ApiKeyScope.ADMIN));

        mockMvc.perform(get("/v1/locations").header("Authorization", "Bearer " + admin.value()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anIngestKeyPassesOnlyOnSegmentUploads() throws Exception {
        ApiKeyToken ingest = apiKeys.create("default", "agent test", Set.of(ApiKeyScope.INGEST));

        assertThat(mockMvc.perform(put(SEGMENT).header("Authorization", "Bearer " + ingest.value()))
                .andReturn().getResponse().getStatus()).isNotIn(401, 403);
        forbidden(mockMvc.perform(get("/v1/locations").header("Authorization", "Bearer " + ingest.value())));
    }

    @Test
    void anAdminKeyCannotUploadSegments() throws Exception {
        ApiKeyToken admin = apiKeys.create("default", "admin test", Set.of(ApiKeyScope.ADMIN));

        forbidden(mockMvc.perform(put(SEGMENT).header("Authorization", "Bearer " + admin.value())));
    }

    @Test
    void everyKindOfBadCredentialGetsTheSameAnswer() throws Exception {
        ApiKeyToken valid = apiKeys.create("default", "victim", Set.of(ApiKeyScope.ADMIN));
        ApiKeyToken revoked = apiKeys.create("default", "revoked", Set.of(ApiKeyScope.ADMIN));
        apiKeys.revoke("default", revoked.id());
        String wrongSecret = valid.value().substring(0, 13) + ApiKeyToken.generate(new java.security.SecureRandom())
                .value().substring(13);

        unauthorized(mockMvc.perform(get("/v1/locations")));
        unauthorized(mockMvc.perform(get("/v1/locations").header("Authorization", "Basic dXNlcjpwYXNz")));
        unauthorized(mockMvc.perform(get("/v1/locations").header("Authorization", "Bearer not-a-key")));
        unauthorized(mockMvc.perform(get("/v1/locations").header("Authorization", "Bearer " + wrongSecret)));
        unauthorized(mockMvc.perform(get("/v1/locations").header("Authorization",
                "Bearer " + ApiKeyToken.generate(new java.security.SecureRandom()).value())));
        unauthorized(mockMvc.perform(get("/v1/locations").header("Authorization", "Bearer " + revoked.value())));
    }

    @Test
    void anyPathNotListedIsDenied() throws Exception {
        ApiKeyToken admin = apiKeys.create("default", "admin test", Set.of(ApiKeyScope.ADMIN, ApiKeyScope.INGEST));

        forbidden(mockMvc.perform(get("/actuator/env").header("Authorization", "Bearer " + admin.value())));
        forbidden(mockMvc.perform(get("/v1/segments").header("Authorization", "Bearer " + admin.value())));
    }

    @Test
    void recordsWhenAKeyIsUsedButNotWhenItFails() throws Exception {
        ApiKeyToken used = apiKeys.create("default", "used", Set.of(ApiKeyScope.ADMIN));
        ApiKeyToken unused = apiKeys.create("default", "unused", Set.of(ApiKeyScope.ADMIN));

        mockMvc.perform(get("/v1/locations").header("Authorization", "Bearer " + used.value()));
        mockMvc.perform(get("/v1/locations").header("Authorization",
                "Bearer " + unused.value().substring(0, 13) + "x".repeat(43)));

        assertThat(lastUsed(used.id())).isNotNull();
        assertThat(lastUsed(unused.id())).isNull();
    }

    @Test
    void theSecretIsNeverStored() {
        ApiKeyToken token = apiKeys.create("default", "stored", Set.of(ApiKeyScope.ADMIN));
        String secret = token.value().substring(13);

        String row = jdbc.sql("SELECT row_to_json(k)::text FROM api_key k WHERE id = :id")
                .param("id", token.id()).query(String.class).single();
        assertThat(row).doesNotContain(secret);
    }

    private Object lastUsed(String id) {
        return jdbc.sql("SELECT last_used_at FROM api_key WHERE id = :id").param("id", id)
                .query((rs, row) -> rs.getObject(1)).list().getFirst();
    }

    private static void unauthorized(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("Missing, malformed, unknown or revoked API key."));
    }

    private static void forbidden(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.status").value(403));
    }
}
