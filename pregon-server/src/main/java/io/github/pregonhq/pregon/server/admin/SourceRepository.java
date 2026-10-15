package io.github.pregonhq.pregon.server.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Every query filters by tenant: an id from another tenant behaves as if it did not exist. */
@Repository
class SourceRepository {

    private static final TypeReference<List<Schedules.WindowJson>> WINDOWS = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {
    };

    record Fields(UUID locationId, String name, String kind, String role, List<Schedules.WindowJson> operatingHours,
            SourceController.Interaction interaction, Map<String, Object> audio) {
    }

    record Row(UUID id, Fields fields, String status, long version, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    SourceRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Row insert(String tenantId, Fields f) {
        return jdbc.sql("""
                INSERT INTO source (tenant_id, location_id, name, kind, role, operating_hours, interaction, audio)
                VALUES (:tenantId, :locationId, :name, :kind, :role, :hours::jsonb, :interaction::jsonb, :audio::jsonb)
                RETURNING *""")
                .param("tenantId", tenantId).params(params(f))
                .query(this::row).single();
    }

    Optional<Row> find(String tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM source WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id)
                .query(this::row).optional();
    }

    List<Row> page(String tenantId, UUID locationId, String status, UUID after, int limit) {
        return jdbc.sql("""
                SELECT * FROM source
                WHERE tenant_id = :tenantId AND status = :status
                  AND (CAST(:locationId AS uuid) IS NULL OR location_id = :locationId)
                  AND (CAST(:after AS uuid) IS NULL OR id > :after)
                ORDER BY id LIMIT :limit""")
                .param("tenantId", tenantId).param("status", status).param("locationId", locationId)
                .param("after", after).param("limit", limit)
                .query(this::row).list();
    }

    Optional<Row> replace(String tenantId, UUID id, long expectedVersion, Fields f) {
        return jdbc.sql("""
                UPDATE source SET name = :name, kind = :kind, role = :role, operating_hours = :hours::jsonb,
                    interaction = :interaction::jsonb, audio = :audio::jsonb, version = version + 1, updated_at = now()
                WHERE tenant_id = :tenantId AND id = :id AND version = :version AND status = 'ACTIVE'
                RETURNING *""")
                .param("tenantId", tenantId).param("id", id).param("version", expectedVersion).params(params(f))
                .query(this::row).optional();
    }

    Optional<Row> archive(String tenantId, UUID id) {
        return jdbc.sql("""
                UPDATE source SET status = 'ARCHIVED', version = version + 1, updated_at = now()
                WHERE tenant_id = :tenantId AND id = :id AND status = 'ACTIVE'
                RETURNING *""")
                .param("tenantId", tenantId).param("id", id)
                .query(this::row).optional();
    }

    private Map<String, Object> params(Fields f) {
        var params = new java.util.HashMap<String, Object>();
        params.put("locationId", f.locationId());
        params.put("name", f.name());
        params.put("kind", f.kind());
        params.put("role", f.role());
        params.put("hours", json.writeValueAsString(f.operatingHours()));
        params.put("interaction", json.writeValueAsString(f.interaction()));
        params.put("audio", json.writeValueAsString(f.audio()));
        return params;
    }

    private Row row(ResultSet rs, int rowNum) throws SQLException {
        Fields fields = new Fields(rs.getObject("location_id", UUID.class), rs.getString("name"), rs.getString("kind"),
                rs.getString("role"), json.readValue(rs.getString("operating_hours"), WINDOWS),
                json.readValue(rs.getString("interaction"), SourceController.Interaction.class),
                json.readValue(rs.getString("audio"), OBJECT));
        return new Row(rs.getObject("id", UUID.class), fields, rs.getString("status"), rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }
}
