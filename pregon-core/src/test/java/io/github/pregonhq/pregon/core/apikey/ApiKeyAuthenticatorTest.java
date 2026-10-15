package io.github.pregonhq.pregon.core.apikey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ApiKeyAuthenticatorTest {

    private final SecureRandom random = new SecureRandom();
    private final InMemoryStore store = new InMemoryStore();
    private final ApiKeyAuthenticator authenticator = new ApiKeyAuthenticator(store);

    @Test
    void authenticatesAValidKeyWithItsTenantAndScopes() {
        ApiKeyToken token = store.add("acme", Set.of(ApiKeyScope.INGEST), null);

        var result = authenticator.authenticate(token.value());

        var authenticated = assertInstanceOf(ApiKeyAuthenticator.Result.Authenticated.class, result);
        assertEquals(token.id(), authenticated.keyId());
        assertEquals("acme", authenticated.tenantId());
        assertEquals(Set.of(ApiKeyScope.INGEST), authenticated.scopes());
        assertEquals(List.of(token.id()), store.used);
    }

    @Test
    void rejectsAWrongSecretForAnExistingId() {
        ApiKeyToken token = store.add("acme", Set.of(ApiKeyScope.ADMIN), null);
        String forged = token.value().substring(0, 13) + ApiKeyToken.generate(random).value().substring(13);

        assertRejected(authenticator.authenticate(forged));
    }

    @Test
    void rejectsAnUnknownKey() {
        assertRejected(authenticator.authenticate(ApiKeyToken.generate(random).value()));
    }

    @Test
    void rejectsARevokedKey() {
        ApiKeyToken token = store.add("acme", Set.of(ApiKeyScope.ADMIN), Instant.parse("2026-10-01T00:00:00Z"));

        assertRejected(authenticator.authenticate(token.value()));
    }

    @Test
    void rejectsMalformedInputWithoutTouchingTheStore() {
        assertRejected(authenticator.authenticate("not-a-key"));
        assertRejected(authenticator.authenticate(null));
        assertTrue(store.lookups == 0);
    }

    private void assertRejected(ApiKeyAuthenticator.Result result) {
        assertInstanceOf(ApiKeyAuthenticator.Result.Rejected.class, result);
        assertTrue(store.used.isEmpty(), "a rejected key must not be recorded as used");
    }

    private final class InMemoryStore implements ApiKeyStore {
        private final Map<String, StoredApiKey> keys = new HashMap<>();
        private final List<String> used = new ArrayList<>();
        private int lookups;

        ApiKeyToken add(String tenantId, Set<ApiKeyScope> scopes, Instant revokedAt) {
            ApiKeyToken token = ApiKeyToken.generate(random);
            keys.put(token.id(), new StoredApiKey(token.id(), tenantId, scopes, token.secretHash(), revokedAt));
            return token;
        }

        @Override
        public Optional<StoredApiKey> findById(String id) {
            lookups++;
            return Optional.ofNullable(keys.get(id));
        }

        @Override
        public void recordUse(String id) {
            used.add(id);
        }
    }
}
