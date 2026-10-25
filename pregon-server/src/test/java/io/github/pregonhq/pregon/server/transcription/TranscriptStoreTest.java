package io.github.pregonhq.pregon.server.transcription;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import io.github.pregonhq.pregon.core.ingest.SegmentQueue;
import io.github.pregonhq.pregon.server.InfrastructureContainers;
import io.github.pregonhq.pregon.server.ingest.MutableClock;
import io.github.pregonhq.pregon.spi.speech.SpeechSpan;
import io.github.pregonhq.pregon.spi.speech.TranscriptionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** The {@code transcript} table through the store (V4): what is copied from the segment, idempotency, tenancy, purge. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TranscriptStoreTest {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        InfrastructureContainers.register(registry);
    }

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    MutableClock clock;
    TranscriptStore store;

    @BeforeEach
    void build() {
        clock = new MutableClock(Instant.parse("2026-10-25T12:00:00Z"));
        store = new TranscriptStore(jdbc, clock, json);
    }

    @Test
    void savingCopiesTheCaptureWindowAndTheSourceFromTheSegment() {
        String tenant = newTenant();
        Segment segment = newSegment(tenant);
        SpeechSpan span = new SpeechSpan(Duration.ofMillis(500), Duration.ofMillis(2500), "¿Quiere papas grandes?", -0.31, 0.02, 1.1);

        store.save(job(segment), transcribed(segment.id(), "¿Quiere papas grandes?", List.of(span)));

        TranscriptStore.Stored stored = store.find(tenant, segment.id()).orElseThrow();
        assertThat(stored.text()).isEqualTo("¿Quiere papas grandes?");
        assertThat(stored.language()).isEqualTo("es");
        assertThat(stored.audioDuration()).isEqualTo(Duration.ofSeconds(10));
        assertThat(stored.processingTime()).isEqualTo(Duration.ofMillis(1234));
        assertThat(stored.spans()).containsExactly(span);
        assertThat(stored.transcribedAt()).isEqualTo(clock.instant());
        assertThat(jdbc.sql("SELECT source_id FROM transcript WHERE segment_id = :id").param("id", segment.id()).query(UUID.class).single())
                .isEqualTo(segment.sourceId());
        assertThat(jdbc.sql("SELECT capture_start FROM transcript WHERE segment_id = :id").param("id", segment.id())
                .query(OffsetDateTime.class).single().toInstant()).isEqualTo(segment.captureEnd().minusSeconds(10));
    }

    @Test
    void aSpanWithoutFiguresComesBackWithoutFigures() {
        String tenant = newTenant();
        Segment segment = newSegment(tenant);
        SpeechSpan bare = new SpeechSpan(Duration.ZERO, Duration.ofSeconds(1), "hola", Double.NaN, Double.NaN, Double.NaN);

        store.save(job(segment), transcribed(segment.id(), "hola", List.of(bare)));

        SpeechSpan read = store.find(tenant, segment.id()).orElseThrow().spans().get(0);
        assertThat(read.hasConfidence()).isFalse();
        assertThat(read.text()).isEqualTo("hola");
    }

    @Test
    void savingTwiceKeepsTheFirstTranscript() {
        String tenant = newTenant();
        Segment segment = newSegment(tenant);

        store.save(job(segment), transcribed(segment.id(), "first", List.of()));
        store.save(job(segment), transcribed(segment.id(), "second", List.of()));

        assertThat(store.find(tenant, segment.id()).orElseThrow().text()).isEqualTo("first");
        assertThat(jdbc.sql("SELECT count(*) FROM transcript WHERE segment_id = :id").param("id", segment.id()).query(Long.class).single())
                .isEqualTo(1L);
    }

    @Test
    void aTranscriptIsOnlyFoundByItsTenant() {
        String tenant = newTenant();
        String other = newTenant();
        Segment segment = newSegment(tenant);
        store.save(job(segment), transcribed(segment.id(), "hola", List.of()));

        assertThat(store.find(other, segment.id())).isEmpty();
        assertThat(store.find(tenant, segment.id())).isPresent();
    }

    @Test
    void nothingIsSavedForASegmentThatDoesNotExist() {
        String tenant = newTenant();
        UUID unknown = UUID.randomUUID();

        store.save(new SegmentQueue.Job(unknown, tenant, UUID.randomUUID(), 1, clock.instant(), new byte[1]),
                transcribed(unknown, "hola", List.of()));

        assertThat(store.find(tenant, unknown)).isEmpty();
    }

    @Test
    void purgingDeletesOnlyTranscriptsOlderThanTheCutoff() {
        String tenant = newTenant();
        Segment old = newSegment(tenant);
        store.save(job(old), transcribed(old.id(), "old", List.of()));
        clock.advance(Duration.ofDays(10));
        Segment recent = newSegment(tenant);
        store.save(job(recent), transcribed(recent.id(), "recent", List.of()));

        int purged = store.purgeOlderThan(clock.instant().minus(Duration.ofDays(7)));

        // The table is shared with the other tests of this run, so only a lower bound is exact.
        assertThat(purged).isGreaterThanOrEqualTo(1);
        assertThat(store.find(tenant, old.id())).isEmpty();
        assertThat(store.find(tenant, recent.id())).isPresent();
    }

    private record Segment(UUID id, UUID sourceId, Instant captureEnd) {
    }

    private SegmentQueue.Job job(Segment segment) {
        return new SegmentQueue.Job(segment.id(), tenantOf(segment.id()), segment.sourceId(), 1, clock.instant().plusSeconds(360), new byte[1]);
    }

    private String tenantOf(UUID segmentId) {
        return jdbc.sql("SELECT tenant_id FROM segment WHERE id = :id").param("id", segmentId).query(String.class).single();
    }

    private static TranscriptionResult.Transcribed transcribed(UUID id, String text, List<SpeechSpan> spans) {
        return new TranscriptionResult.Transcribed(id, text, "es", Duration.ofSeconds(10), spans, Duration.ofMillis(1234));
    }

    private String newTenant() {
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

    private Segment newSegment(String tenant) {
        UUID id = UUID.randomUUID();
        UUID source = jdbc.sql("SELECT id FROM source WHERE tenant_id = :tenant").param("tenant", tenant).query(UUID.class).single();
        OffsetDateTime end = clock.instant().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO segment (tenant_id, id, source_id, capture_start, capture_end, voiced_millis, audio_sha256,
                    audio_bytes, received_at, clock_skew_suspected, state, attempt)
                VALUES (:tenant, :id, :source, :start, :end, 6400, :sha, 1, :end, false, 'LEASED', 1)""")
                .param("tenant", tenant).param("id", id).param("source", source)
                .param("start", end.minusSeconds(10)).param("end", end).param("sha", new byte[32])
                .update();
        return new Segment(id, source, end.toInstant());
    }
}
