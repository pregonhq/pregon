package io.github.pregonhq.pregon.core.apikey;

import java.util.Objects;
import java.util.Set;

/**
 * Decides whether a presented key authenticates a caller.
 *
 * <p>Every failure produces the same {@link Result.Rejected} on purpose: telling "unknown id"
 * apart from "wrong secret" or "revoked" would let a caller probe which keys exist.
 */
public final class ApiKeyAuthenticator {

    private final ApiKeyStore store;

    public ApiKeyAuthenticator(ApiKeyStore store) {
        this.store = Objects.requireNonNull(store);
    }

    public Result authenticate(String presentedKey) {
        var token = ApiKeyToken.parse(presentedKey);
        if (token.isEmpty()) {
            return Result.REJECTED;
        }
        var stored = store.findById(token.get().id());
        if (stored.isEmpty() || !stored.get().matches(token.get()) || stored.get().revoked()) {
            return Result.REJECTED;
        }
        store.recordUse(stored.get().id());
        return new Result.Authenticated(stored.get().id(), stored.get().tenantId(), stored.get().scopes());
    }

    public sealed interface Result {

        Result REJECTED = new Rejected();

        record Authenticated(String keyId, String tenantId, Set<ApiKeyScope> scopes) implements Result {
        }

        record Rejected() implements Result {
        }
    }
}
