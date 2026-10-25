package io.github.pregonhq.pregon.server.ingest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.pregonhq.pregon.core.ingest.SegmentQueue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The queue of ADR-0024 §4 on the {@code segment} table itself (ADR-0025 §9): a claim is one
 * {@code UPDATE ... FOR UPDATE SKIP LOCKED} in capture order, and the attempt number is the lease
 * token that {@code complete} and {@code fail} must present.
 *
 * <p>Time comes from the injected {@link Clock}, never from the database, so a lease can expire in a
 * test by moving the clock. Audio is deleted in the same transaction that ends the job.
 */
public final class PostgresSegmentQueue implements SegmentQueue {

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final int maxAttempts;

    public PostgresSegmentQueue(JdbcClient jdbc, TransactionTemplate transaction, Clock clock, int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1, got " + maxAttempts);
        }
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public List<Job> claim(int max, Duration lease) {
        if (max < 1) {
            return List.of();
        }
        Instant now = now();
        return transaction.execute(status -> {
            // A lease that expired after the last allowed attempt is a worker that died: the segment
            // fails here, before the claim, so the claim never hands it out again.
            List<UUID> exhausted = jdbc.sql("""
                    UPDATE segment SET state = 'FAILED', lease_until = NULL,
                        failure_reason = 'lease expired after the last attempt'
                    WHERE (tenant_id, id) IN (
                        SELECT tenant_id, id FROM segment
                        WHERE state = 'LEASED' AND lease_until < :now AND attempt >= :maxAttempts
                        FOR UPDATE SKIP LOCKED)
                    RETURNING id""")
                    .param("now", offset(now)).param("maxAttempts", maxAttempts)
                    .query(UUID.class).list();
            deleteAudio(exhausted);

            record Leased(UUID id, String tenantId, UUID sourceId, int attempt, Instant leaseUntil) {
            }
            List<Leased> rows = jdbc.sql("""
                    UPDATE segment SET state = 'LEASED', lease_until = :leaseUntil, attempt = attempt + 1
                    WHERE (tenant_id, id) IN (
                        SELECT tenant_id, id FROM segment
                        WHERE state = 'QUEUED' OR (state = 'LEASED' AND lease_until < :now)
                        ORDER BY id
                        LIMIT :max
                        FOR UPDATE SKIP LOCKED)
                    RETURNING tenant_id, id, source_id, attempt, lease_until""")
                    .param("now", offset(now)).param("leaseUntil", offset(now.plus(lease))).param("max", max)
                    .query((rs, i) -> new Leased(rs.getObject("id", UUID.class), rs.getString("tenant_id"),
                            rs.getObject("source_id", UUID.class), rs.getInt("attempt"),
                            rs.getObject("lease_until", OffsetDateTime.class).toInstant()))
                    .list();
            if (rows.isEmpty()) {
                return List.of();
            }
            List<UUID> ids = rows.stream().map(Leased::id).toList();
            Map<UUID, byte[]> audio = new HashMap<>();
            jdbc.sql("SELECT segment_id, bytes FROM segment_audio WHERE segment_id IN (:ids)")
                    .param("ids", ids)
                    .query((rs, i) -> audio.put(rs.getObject("segment_id", UUID.class), rs.getBytes("bytes")))
                    .list();
            return rows.stream()
                    .map(row -> new Job(row.id(), row.tenantId(), row.sourceId(), row.attempt(), row.leaseUntil(),
                            audio.getOrDefault(row.id(), new byte[0])))
                    .sorted((a, b) -> a.segmentId().compareTo(b.segmentId()))
                    .toList();
        });
    }

    @Override
    public CompleteOutcome complete(String tenantId, UUID segmentId, int attempt) {
        return transaction.execute(status -> {
            int updated = jdbc.sql("""
                    UPDATE segment SET state = 'TRANSCRIBED', lease_until = NULL
                    WHERE tenant_id = :tenant AND id = :id AND attempt = :attempt AND state = 'LEASED'""")
                    .param("tenant", tenantId).param("id", segmentId).param("attempt", attempt)
                    .update();
            if (updated == 0) {
                return CompleteOutcome.STALE;
            }
            deleteAudio(tenantId, segmentId);
            return CompleteOutcome.COMPLETED;
        });
    }

    @Override
    public FailOutcome fail(String tenantId, UUID segmentId, int attempt, String reason) {
        return transaction.execute(status -> {
            boolean last = attempt >= maxAttempts;
            int updated = jdbc.sql("""
                    UPDATE segment SET state = :state, lease_until = NULL, failure_reason = :reason
                    WHERE tenant_id = :tenant AND id = :id AND attempt = :attempt AND state = 'LEASED'""")
                    .param("state", last ? "FAILED" : "QUEUED").param("reason", reason)
                    .param("tenant", tenantId).param("id", segmentId).param("attempt", attempt)
                    .update();
            if (updated == 0) {
                return FailOutcome.STALE;
            }
            if (last) {
                deleteAudio(tenantId, segmentId);
                return FailOutcome.FAILED;
            }
            return FailOutcome.REQUEUED;
        });
    }

    private void deleteAudio(String tenantId, UUID segmentId) {
        jdbc.sql("DELETE FROM segment_audio WHERE tenant_id = :tenant AND segment_id = :id")
                .param("tenant", tenantId).param("id", segmentId).update();
    }

    private void deleteAudio(List<UUID> ids) {
        if (!ids.isEmpty()) {
            jdbc.sql("DELETE FROM segment_audio WHERE segment_id IN (:ids)").param("ids", ids).update();
        }
    }

    /** PostgreSQL keeps microseconds; the lease must round-trip exactly. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant.atOffset(java.time.ZoneOffset.UTC);
    }
}
