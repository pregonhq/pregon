package io.github.pregonhq.pregon.server.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Every query filters by tenant: an id from another tenant behaves as if it did not exist. */
@Repository
class LocationRepository {

    private static final TypeReference<List<Schedules.ShiftJson>> SHIFTS = new TypeReference<>() {
    };

    record Row(UUID id, String name, String timezone, List<Schedules.ShiftJson> shifts, String status, long version,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    LocationRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Row insert(String tenantId, String name, String timezone, List<Schedules.ShiftJson> shifts) {
        return jdbc.sql("""
                INSERT INTO location (tenant_id, name, timezone, shifts)
                VALUES (:tenantId, :name, :timezone, :shifts::jsonb)
                RETURNING *""")
                .param("tenantId", tenantId).param("name", name).param("timezone", timezone)
                .param("shifts", json.writeValueAsString(shifts))
                .query(this::row).single();
    }

    Optional<Row> find(String tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM location WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id)
                .query(this::row).optional();
    }

    List<Row> page(String tenantId, String status, UUID after, int limit) {
        return jdbc.sql("""
                SELECT * FROM location
                WHERE tenant_id = :tenantId AND status = :status AND (CAST(:after AS uuid) IS NULL OR id > :after)
                ORDER BY id LIMIT :limit""")
                .param("tenantId", tenantId).param("status", status).param("after", after).param("limit", limit)
                .query(this::row).list();
    }

    /** Empty when the version did not match (or the location is gone). */
    Optional<Row> replace(String tenantId, UUID id, long expectedVersion, String name, String timezone,
            List<Schedules.ShiftJson> shifts) {
        return jdbc.sql("""
                UPDATE location SET name = :name, timezone = :timezone, shifts = :shifts::jsonb,
                    version = version + 1, updated_at = now()
                WHERE tenant_id = :tenantId AND id = :id AND version = :version AND status = 'ACTIVE'
                RETURNING *""")
                .param("tenantId", tenantId).param("id", id).param("version", expectedVersion)
                .param("name", name).param("timezone", timezone).param("shifts", json.writeValueAsString(shifts))
                .query(this::row).optional();
    }

    boolean hasActiveSources(String tenantId, UUID id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM source WHERE tenant_id = :tenantId AND location_id = :id AND status = 'ACTIVE')")
                .param("tenantId", tenantId).param("id", id).query(Boolean.class).single();
    }

    Optional<Row> archive(String tenantId, UUID id) {
        return jdbc.sql("""
                UPDATE location SET status = 'ARCHIVED', version = version + 1, updated_at = now()
                WHERE tenant_id = :tenantId AND id = :id AND status = 'ACTIVE'
                RETURNING *""")
                .param("tenantId", tenantId).param("id", id)
                .query(this::row).optional();
    }

    private Row row(ResultSet rs, int rowNum) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("timezone"),
                json.readValue(rs.getString("shifts"), SHIFTS), rs.getString("status"), rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }
}
