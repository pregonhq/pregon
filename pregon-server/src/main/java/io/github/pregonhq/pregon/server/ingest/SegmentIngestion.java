package io.github.pregonhq.pregon.server.ingest;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Accepts a segment exactly once (ADR-0024 §3, ADR-0025 §5 and §6): validates before storing,
 * records the segment, its audio and its place in the queue in one transaction, and answers a
 * retry with the original receipt even when the queue is full.
 *
 * <p>Order of the checks, and why: the source must exist in the caller's tenant ({@code 404},
 * identical for another tenant's source); then one statement inserts the segment or finds the
 * existing one. A found segment is compared by value: same content is the receipt ({@code 200}),
 * other content is an agent bug ({@code 409}). Only a new segment is checked against an archived
 * source ({@code 409}) and the quotas ({@code 503}), so a retry of something already accepted is
 * never refused for a state that changed after it was accepted.
 */
final class SegmentIngestion {

    private static final Logger log = LoggerFactory.getLogger(SegmentIngestion.class);

    /** What the agent gets back; {@code created} tells {@code 202} from {@code 200}. */
    record Receipt(UUID segmentId, Instant receivedAt, boolean clockSkewSuspected, boolean created) {
    }

    private record Existing(Instant receivedAt, boolean clockSkewSuspected, UUID sourceId, Instant captureStart,
            Instant captureEnd, long voicedMillis, byte[] audioSha256) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final IngestProperties properties;

    SegmentIngestion(JdbcClient jdbc, TransactionTemplate transaction, Clock clock, IngestProperties properties) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.clock = clock;
        this.properties = properties;
    }

    Receipt accept(String tenantId, UUID segmentId, SegmentMetadata metadata, byte[] audio) {
        if (audio.length > properties.maxSegmentBytes()) {
            throw new IngestProblems.TooLarge("The audio is larger than " + properties.maxSegmentBytes() + " bytes.");
        }
        if (!OggOpusCheck.isOggOpus(audio)) {
            throw new IngestProblems.InvalidRequest("The audio part is not an Ogg/Opus stream.");
        }
        String sourceStatus = jdbc.sql("SELECT status FROM source WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenantId).param("id", metadata.sourceId())
                .query(String.class).optional().orElseThrow(IngestProblems.SourceNotFound::new);

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        boolean skew = Duration.between(metadata.captureEnd(), now).abs().compareTo(properties.clockSkewThreshold()) > 0;
        byte[] sha256 = sha256(audio);

        Receipt receipt = transaction.execute(status -> {
            Optional<Existing> existing = insertOrFind(tenantId, segmentId, metadata, audio.length, sha256, now, skew);
            if (existing.isPresent()) {
                return receiptFor(segmentId, metadata, sha256, existing.get());
            }
            if (!"ACTIVE".equals(sourceStatus)) {
                status.setRollbackOnly();
                throw new IngestProblems.Conflict("The source is archived and accepts no segments.");
            }
            checkQuotas(status, tenantId, metadata.sourceId());
            jdbc.sql("INSERT INTO segment_audio (tenant_id, segment_id, bytes) VALUES (:tenant, :id, :audio)")
                    .param("tenant", tenantId).param("id", segmentId).param("audio", audio).update();
            return new Receipt(segmentId, now, skew, true);
        });
        if (receipt.created()) {
            log.debug("accepted segment {} of source {} ({} bytes, skew={})", segmentId, metadata.sourceId(), audio.length, skew);
        }
        return receipt;
    }

    /**
     * One statement records the segment or returns the existing one (ADR-0025 §6). When two uploads
     * of the same id race, the loser's {@code SELECT} may see nothing: only then is the row read again.
     */
    private Optional<Existing> insertOrFind(String tenantId, UUID segmentId, SegmentMetadata m, int audioBytes, byte[] sha256,
            Instant now, boolean skew) {
        Map<String, Object> row = jdbc.sql("""
                WITH ins AS (
                    INSERT INTO segment (tenant_id, id, source_id, capture_start, capture_end, voiced_millis, agent_version,
                        audio_sha256, audio_bytes, received_at, clock_skew_suspected)
                    VALUES (:tenant, :id, :source, :start, :end, :voiced, :agent, :sha, :bytes, :now, :skew)
                    ON CONFLICT (tenant_id, id) DO NOTHING
                    RETURNING id)
                SELECT true AS created, NULL::timestamptz AS received_at, NULL::boolean AS clock_skew_suspected,
                       NULL::uuid AS source_id, NULL::timestamptz AS capture_start, NULL::timestamptz AS capture_end,
                       NULL::bigint AS voiced_millis, NULL::bytea AS audio_sha256
                FROM ins
                UNION ALL
                SELECT false, received_at, clock_skew_suspected, source_id, capture_start, capture_end, voiced_millis, audio_sha256
                FROM segment WHERE tenant_id = :tenant AND id = :id AND NOT EXISTS (SELECT 1 FROM ins)""")
                .param("tenant", tenantId).param("id", segmentId).param("source", m.sourceId())
                .param("start", m.captureStart().atOffset(ZoneOffset.UTC)).param("end", m.captureEnd().atOffset(ZoneOffset.UTC))
                .param("voiced", m.voicedMillis()).param("agent", m.agentVersion()).param("sha", sha256).param("bytes", audioBytes)
                .param("now", now.atOffset(ZoneOffset.UTC)).param("skew", skew)
                .query().listOfRows().stream().findFirst().orElse(null);
        if (row != null && Boolean.TRUE.equals(row.get("created"))) {
            return Optional.empty();
        }
        if (row == null) {
            row = jdbc.sql("""
                    SELECT received_at, clock_skew_suspected, source_id, capture_start, capture_end, voiced_millis, audio_sha256
                    FROM segment WHERE tenant_id = :tenant AND id = :id""")
                    .param("tenant", tenantId).param("id", segmentId)
                    .query().listOfRows().stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("segment " + segmentId + " neither inserted nor found"));
        }
        return Optional.of(new Existing(instant(row.get("received_at")), (Boolean) row.get("clock_skew_suspected"),
                (UUID) row.get("source_id"), instant(row.get("capture_start")), instant(row.get("capture_end")),
                ((Number) row.get("voiced_millis")).longValue(), (byte[]) row.get("audio_sha256")));
    }

    private static Receipt receiptFor(UUID segmentId, SegmentMetadata m, byte[] sha256, Existing e) {
        boolean same = e.sourceId().equals(m.sourceId())
                && e.captureStart().equals(m.captureStart())
                && e.captureEnd().equals(m.captureEnd())
                && e.voicedMillis() == m.voicedMillis()
                && Arrays.equals(e.audioSha256(), sha256);
        if (!same) {
            throw new IngestProblems.Conflict("Segment " + segmentId + " was already accepted with different content. Do not retry.");
        }
        return new Receipt(segmentId, e.receivedAt(), e.clockSkewSuspected(), false);
    }

    /**
     * Quotas as a sum over the queue's working set (ADR-0025 Rollout, step 1): cheap at the sizes of
     * {@code docs/capacity.md}, replaced by in-memory counters in the performance step. The new
     * segment is already in the sum, so "over" means this upload does not fit.
     */
    private void checkQuotas(org.springframework.transaction.TransactionStatus status, String tenantId, UUID sourceId) {
        Map<String, Object> queued = jdbc.sql("""
                SELECT coalesce(sum(audio_bytes), 0) AS tenant_bytes,
                       coalesce(sum(audio_bytes) FILTER (WHERE source_id = :source), 0) AS source_bytes
                FROM segment WHERE tenant_id = :tenant AND state IN ('QUEUED', 'LEASED')""")
                .param("tenant", tenantId).param("source", sourceId)
                .query().singleRow();
        long tenantBytes = ((Number) queued.get("tenant_bytes")).longValue();
        long sourceBytes = ((Number) queued.get("source_bytes")).longValue();
        if (tenantBytes > properties.queueMaxBytes()) {
            status.setRollbackOnly();
            throw new IngestProblems.Throttled("The tenant's transcription queue is full. Keep the segment and retry later.", retryAfterSeconds());
        }
        if (sourceBytes > properties.sourceQuotaBytes()) {
            status.setRollbackOnly();
            throw new IngestProblems.Throttled("This source holds its whole share of the transcription queue. Keep the segment and retry later.",
                    retryAfterSeconds());
        }
    }

    /** No drain rate is observed yet (performance step): the floor is the answer (ADR-0025 §7). */
    private long retryAfterSeconds() {
        return Math.max(1, properties.retryAfterMin().toSeconds());
    }

    /** The driver hands a timestamptz back as java.sql.Timestamp through generic row maps. */
    private static Instant instant(Object value) {
        if (value instanceof OffsetDateTime offset) {
            return offset.toInstant();
        }
        return ((java.sql.Timestamp) value).toInstant();
    }

    private static byte[] sha256(byte[] audio) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(audio);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JVM", e);
        }
    }
}
