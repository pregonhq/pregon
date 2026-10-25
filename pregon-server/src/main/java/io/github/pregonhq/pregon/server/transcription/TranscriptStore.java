package io.github.pregonhq.pregon.server.transcription;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.pregonhq.pregon.core.ingest.SegmentQueue;
import io.github.pregonhq.pregon.spi.speech.SpeechSpan;
import io.github.pregonhq.pregon.spi.speech.TranscriptionResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code transcript} table (V4, ADR-0027): the text of a segment, kept only as long as
 * {@code PREGON_RETENTION_TRANSCRIPTS} says. Saving copies the capture window and the source from
 * the segment row, because the segment's partition is dropped before the transcript may be.
 */
public final class TranscriptStore implements TranscriptionWorker.TranscriptSink {

    /** What the API shows of a stored transcript. */
    public record Stored(String text, String language, Duration audioDuration, Duration processingTime,
            List<SpeechSpan> spans, Instant transcribedAt) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;
    private final JsonMapper json;

    public TranscriptStore(JdbcClient jdbc, Clock clock, JsonMapper json) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.json = json;
    }

    /** Idempotent: a second transcription of the same segment (a stale worker, ADR-0025 §9) changes nothing. */
    @Override
    public void save(SegmentQueue.Job job, TranscriptionResult.Transcribed result) {
        jdbc.sql("""
                INSERT INTO transcript (tenant_id, segment_id, source_id, capture_start, capture_end, language, text, spans,
                                        audio_millis, processing_millis, transcribed_at)
                SELECT s.tenant_id, s.id, s.source_id, s.capture_start, s.capture_end, :language, :text, :spans::jsonb,
                       :audioMillis, :processingMillis, :now
                FROM segment s
                WHERE s.tenant_id = :tenantId AND s.id = :segmentId
                ON CONFLICT (segment_id) DO NOTHING
                """)
                .param("tenantId", job.tenantId())
                .param("segmentId", job.segmentId())
                .param("language", result.language())
                .param("text", result.text())
                .param("spans", json.writeValueAsString(spansJson(result.spans())))
                .param("audioMillis", result.audioDuration().toMillis())
                .param("processingMillis", result.processingTime().toMillis())
                .param("now", clock.instant().truncatedTo(ChronoUnit.MICROS).atOffset(java.time.ZoneOffset.UTC))
                .update();
    }

    public Optional<Stored> find(String tenantId, UUID segmentId) {
        return jdbc.sql("""
                SELECT text, language, spans::text AS spans, audio_millis, processing_millis, transcribed_at
                FROM transcript WHERE tenant_id = :tenantId AND segment_id = :segmentId
                """)
                .param("tenantId", tenantId)
                .param("segmentId", segmentId)
                .query(this::stored)
                .optional();
    }

    /** Deletes transcripts transcribed before {@code cutoff}; returns how many. */
    public int purgeOlderThan(Instant cutoff) {
        return jdbc.sql("DELETE FROM transcript WHERE transcribed_at < :cutoff")
                .param("cutoff", cutoff.atOffset(java.time.ZoneOffset.UTC))
                .update();
    }

    private Stored stored(ResultSet rs, int rowNum) throws SQLException {
        return new Stored(
                rs.getString("text"),
                rs.getString("language"),
                Duration.ofMillis(rs.getLong("audio_millis")),
                Duration.ofMillis(rs.getLong("processing_millis")),
                spans(rs.getString("spans")),
                rs.getObject("transcribed_at", OffsetDateTime.class).toInstant());
    }

    /** Spans as JSON objects with millisecond offsets; a figure the model did not give is simply absent. */
    private static List<Map<String, Object>> spansJson(List<SpeechSpan> spans) {
        List<Map<String, Object>> out = new ArrayList<>(spans.size());
        for (SpeechSpan span : spans) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("startMillis", span.start().toMillis());
            entry.put("endMillis", span.end().toMillis());
            entry.put("text", span.text());
            if (!Double.isNaN(span.avgLogprob())) {
                entry.put("avgLogprob", span.avgLogprob());
            }
            if (!Double.isNaN(span.noSpeechProb())) {
                entry.put("noSpeechProb", span.noSpeechProb());
            }
            if (!Double.isNaN(span.compressionRatio())) {
                entry.put("compressionRatio", span.compressionRatio());
            }
            out.add(entry);
        }
        return out;
    }

    private List<SpeechSpan> spans(String text) {
        List<Map<String, Object>> entries = json.readValue(text, json.getTypeFactory().constructCollectionType(List.class, Map.class));
        List<SpeechSpan> spans = new ArrayList<>(entries.size());
        for (Map<String, Object> entry : entries) {
            spans.add(new SpeechSpan(
                    Duration.ofMillis(((Number) entry.get("startMillis")).longValue()),
                    Duration.ofMillis(((Number) entry.get("endMillis")).longValue()),
                    (String) entry.get("text"),
                    figure(entry.get("avgLogprob")),
                    figure(entry.get("noSpeechProb")),
                    figure(entry.get("compressionRatio"))));
        }
        return List.copyOf(spans);
    }

    private static double figure(Object value) {
        return value == null ? Double.NaN : ((Number) value).doubleValue();
    }
}
