package io.github.pregonhq.pregon.core.apikey;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Set;

/** An API key as the server keeps it: no secret, only its hash (ADR-0018). */
public record StoredApiKey(String id, String tenantId, Set<ApiKeyScope> scopes, byte[] secretHash, Instant revokedAt) {

    public StoredApiKey {
        scopes = Set.copyOf(scopes);
        secretHash = secretHash.clone();
    }

    @Override
    public byte[] secretHash() {
        return secretHash.clone();
    }

    /** Constant-time comparison, so response timing does not reveal how much of a secret was right. */
    boolean matches(ApiKeyToken token) {
        return MessageDigest.isEqual(secretHash, token.secretHash());
    }

    boolean revoked() {
        return revokedAt != null;
    }
}
