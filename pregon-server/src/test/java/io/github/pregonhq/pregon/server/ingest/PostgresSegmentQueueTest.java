package io.github.pregonhq.pregon.server.ingest;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import io.github.pregonhq.pregon.core.ingest.SegmentQueue;
import io.github.pregonhq.pregon.server.InfrastructureContainers;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link SegmentQueueContract} against PostgreSQL, through the same schema ingestion writes (V3). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PostgresSegmentQueueTest extends SegmentQueueContract {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        InfrastructureContainers.register(registry);
    }

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate transaction;

    private MutableClock clock;
    private PostgresSegmentQueue queue;

    @BeforeEach
    void build() {
        // The queue is shared by every tenant, so the contract needs it empty when a test starts.
        jdbc.sql("TRUNCATE segment, segment_audio").update();
        clock = new MutableClock(Instant.now().truncatedTo(ChronoUnit.MICROS));
        queue = new PostgresSegmentQueue(jdbc, transaction, clock, MAX_ATTEMPTS);
    }

    @Override
    protected SegmentQueue queue() {
        return queue;
    }

    @Override
    protected MutableClock clock() {
        return clock;
    }

    @Override
    protected String newTenant() {
        String tenant = "t-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("INSERT INTO tenant (id) VALUES (:id)").param("id", tenant).update();
        UUID location = jdbc.sql("""
                INSERT INTO location (tenant_id, name, timezone, shifts) VALUES (:tenant, 'Centro', 'America/Mexico_City', '[]'::jsonb)
                RETURNING id""").param("tenant", tenant).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO source (tenant_id, location_id, name, kind, operating_hours, interaction, audio)
                VALUES (:tenant, :location, 'Main counter', 'MICROPHONE', '[]'::jsonb, '{}'::jsonb, '{}'::jsonb)""")
                .param("tenant", tenant).param("location", location).update();
        return tenant;
    }

    @Override
    protected UUID enqueue(String tenantId, int bytes) {
        UUID id = nextId();
        UUID source = jdbc.sql("SELECT id FROM source WHERE tenant_id = :tenant").param("tenant", tenantId).query(UUID.class).single();
        byte[] audio = new byte[bytes];
        OffsetDateTime end = clock.instant().atOffset(ZoneOffset.UTC);
        transaction.executeWithoutResult(status -> {
            jdbc.sql("""
                    INSERT INTO segment (tenant_id, id, source_id, capture_start, capture_end, voiced_millis, audio_sha256,
                        audio_bytes, received_at, clock_skew_suspected)
                    VALUES (:tenant, :id, :source, :start, :end, 6400, :sha, :bytes, :end, false)""")
                    .param("tenant", tenantId).param("id", id).param("source", source)
                    .param("start", end.minusSeconds(10)).param("end", end).param("sha", sha256(audio)).param("bytes", bytes)
                    .update();
            jdbc.sql("INSERT INTO segment_audio (tenant_id, segment_id, bytes) VALUES (:tenant, :id, :audio)")
                    .param("tenant", tenantId).param("id", id).param("audio", audio).update();
        });
        return id;
    }

    @Override
    protected String stateOf(UUID segmentId) {
        return jdbc.sql("SELECT state FROM segment WHERE id = :id").param("id", segmentId).query(String.class).single();
    }

    @Override
    protected boolean audioExists(UUID segmentId) {
        return jdbc.sql("SELECT count(*) FROM segment_audio WHERE segment_id = :id").param("id", segmentId)
                .query(Long.class).single() > 0;
    }

    @Override
    protected int attemptOf(UUID segmentId) {
        return jdbc.sql("SELECT attempt FROM segment WHERE id = :id").param("id", segmentId).query(Integer.class).single();
    }

    private static final AtomicLong SEQUENCE = new AtomicLong();

    /**
     * A UUIDv7 of the clock's current millisecond with a strictly increasing low part, so segments
     * enqueued within the same millisecond still sort in the order they were enqueued.
     */
    private UUID nextId() {
        long sequence = SEQUENCE.incrementAndGet();
        long msb = (clock.instant().toEpochMilli() << 16) | 0x7000L | (sequence >>> 32 & 0x0FFFL);
        long lsb = 0x8000_0000_0000_0000L | (sequence & 0x3FFF_FFFF_FFFF_FFFFL);
        return new UUID(msb, lsb);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
