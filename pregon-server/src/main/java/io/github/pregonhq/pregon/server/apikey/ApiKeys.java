package io.github.pregonhq.pregon.server.apikey;

import java.security.SecureRandom;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.github.pregonhq.pregon.core.apikey.ApiKeyScope;
import io.github.pregonhq.pregon.core.apikey.ApiKeyStore;
import io.github.pregonhq.pregon.core.apikey.ApiKeyToken;
import io.github.pregonhq.pregon.core.apikey.StoredApiKey;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** PostgreSQL storage of API keys, used by authentication and by the {@code pregon keys} command. */
@Repository
public class ApiKeys implements ApiKeyStore {

    private static final int ID_COLLISION_RETRIES = 5;

    private final JdbcClient jdbc;
    private final SecureRandom random = new SecureRandom();

    public ApiKeys(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates a key and returns it; this is the only moment its secret exists outside the client. */
    public ApiKeyToken create(String tenantId, String name, Set<ApiKeyScope> scopes) {
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException("An API key needs at least one scope");
        }
        for (int attempt = 0; attempt < ID_COLLISION_RETRIES; attempt++) {
            ApiKeyToken token = ApiKeyToken.generate(random);
            try {
                jdbc.sql("""
                        INSERT INTO api_key (id, tenant_id, name, scopes, secret_hash)
                        VALUES (:id, :tenantId, :name, :scopes::text[], :secretHash)""")
                        .param("id", token.id())
                        .param("tenantId", tenantId)
                        .param("name", name)
                        .param("scopes", scopes.stream().map(Enum::name).sorted().toArray(String[]::new))
                        .param("secretHash", token.secretHash())
                        .update();
                return token;
            } catch (DuplicateKeyException idTaken) {
                // 62^8 ids make this rare; a new random id is the whole fix.
            }
        }
        throw new IllegalStateException("Could not find a free API key id after " + ID_COLLISION_RETRIES + " attempts");
    }

    @Override
    public Optional<StoredApiKey> findById(String id) {
        return jdbc.sql("SELECT id, tenant_id, scopes, secret_hash, revoked_at FROM api_key WHERE id = :id")
                .param("id", id)
                .query((rs, row) -> new StoredApiKey(
                        rs.getString("id"),
                        rs.getString("tenant_id"),
                        scopes(rs.getArray("scopes")),
                        rs.getBytes("secret_hash"),
                        instant(rs, "revoked_at")))
                .optional();
    }

    /** At most one write per key per minute, so busy agents do not turn every request into a write. */
    @Override
    public void recordUse(String id) {
        jdbc.sql("""
                UPDATE api_key SET last_used_at = now()
                WHERE id = :id AND (last_used_at IS NULL OR last_used_at < now() - interval '1 minute')""")
                .param("id", id)
                .update();
    }

    public List<Summary> list(String tenantId) {
        return jdbc.sql("""
                SELECT id, name, scopes, created_at, last_used_at, revoked_at
                FROM api_key WHERE tenant_id = :tenantId ORDER BY created_at""")
                .param("tenantId", tenantId)
                .query((rs, row) -> new Summary(
                        rs.getString("id"),
                        rs.getString("name"),
                        scopes(rs.getArray("scopes")),
                        instant(rs, "created_at"),
                        instant(rs, "last_used_at"),
                        instant(rs, "revoked_at")))
                .list();
    }

    /** Revokes a key; revoking twice keeps the first revocation time. */
    public boolean revoke(String tenantId, String id) {
        jdbc.sql("UPDATE api_key SET revoked_at = now() WHERE id = :id AND tenant_id = :tenantId AND revoked_at IS NULL")
                .param("id", id)
                .param("tenantId", tenantId)
                .update();
        return jdbc.sql("SELECT count(*) FROM api_key WHERE id = :id AND tenant_id = :tenantId")
                .param("id", id)
                .param("tenantId", tenantId)
                .query(Integer.class)
                .single() == 1;
    }

    public record Summary(String id, String name, Set<ApiKeyScope> scopes, Instant createdAt, Instant lastUsedAt,
            Instant revokedAt) {
    }

    private static Set<ApiKeyScope> scopes(Array array) throws SQLException {
        Set<ApiKeyScope> scopes = EnumSet.noneOf(ApiKeyScope.class);
        Arrays.stream((String[]) array.getArray()).map(ApiKeyScope::valueOf).forEach(scopes::add);
        return scopes;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
