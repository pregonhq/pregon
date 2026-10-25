package io.github.pregonhq.pregon.server.transcription;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.pregonhq.pregon.server.security.ApiKeyPrincipal;
import io.github.pregonhq.pregon.spi.speech.SpeechSpan;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/segments/{segmentId}} (Admin API, ADR-0027): where a segment is in its life,
 * QUEUED to TRANSCRIBED or FAILED, and its transcript when retention keeps one. The tenant comes
 * from the API key; a segment of another tenant does not exist (ADR-0018, ADR-0024).
 */
@RestController
class SegmentStatusController {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SpanJson(String start, String end, String text, Double avgLogprob, Double noSpeechProb, Double compressionRatio) {
        static SpanJson of(SpeechSpan span) {
            return new SpanJson(span.start().toString(), span.end().toString(), span.text(),
                    figure(span.avgLogprob()), figure(span.noSpeechProb()), figure(span.compressionRatio()));
        }

        private static Double figure(double value) {
            return Double.isNaN(value) ? null : value;
        }
    }

    record TranscriptJson(String text, String language, String audioDuration, String processingTime, List<SpanJson> spans,
            Instant transcribedAt) {
        static TranscriptJson of(TranscriptStore.Stored stored) {
            return new TranscriptJson(stored.text(), stored.language(), stored.audioDuration().toString(),
                    stored.processingTime().toString(), stored.spans().stream().map(SpanJson::of).toList(), stored.transcribedAt());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SegmentStatus(UUID segmentId, UUID sourceId, String state, int attempt, Instant receivedAt, Instant captureStart,
            Instant captureEnd, String voicedDuration, boolean clockSkewSuspected, String failureReason, TranscriptJson transcript) {
    }

    private record Row(UUID sourceId, String state, int attempt, Instant receivedAt, Instant captureStart, Instant captureEnd,
            long voicedMillis, boolean clockSkewSuspected, String failureReason) {
    }

    private final JdbcClient jdbc;
    private final TranscriptStore transcripts;

    SegmentStatusController(JdbcClient jdbc, TranscriptStore transcripts) {
        this.jdbc = jdbc;
        this.transcripts = transcripts;
    }

    @GetMapping(value = "/v1/segments/{segmentId}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> get(@PathVariable String segmentId) {
        String tenantId = ((ApiKeyPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).tenantId();
        UUID id;
        try {
            id = UUID.fromString(segmentId);
        } catch (IllegalArgumentException notAUuid) {
            return notFound();
        }
        Optional<Row> row = jdbc.sql("""
                SELECT source_id, state, attempt, received_at, capture_start, capture_end, voiced_millis, clock_skew_suspected,
                       failure_reason
                FROM segment WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", id)
                .query(SegmentStatusController::row)
                .optional();
        if (row.isEmpty()) {
            return notFound();
        }
        Row r = row.get();
        TranscriptJson transcript = "TRANSCRIBED".equals(r.state())
                ? transcripts.find(tenantId, id).map(TranscriptJson::of).orElse(null) : null;
        return ResponseEntity.ok(new SegmentStatus(id, r.sourceId(), r.state(), r.attempt(), r.receivedAt(), r.captureStart(),
                r.captureEnd(), Duration.ofMillis(r.voicedMillis()).toString(), r.clockSkewSuspected(), r.failureReason(), transcript));
    }

    private static ResponseEntity<ProblemDetail> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No such resource in the caller's tenant."));
    }

    private static Row row(ResultSet rs, int rowNum) throws SQLException {
        return new Row(
                rs.getObject("source_id", UUID.class),
                rs.getString("state"),
                rs.getInt("attempt"),
                rs.getObject("received_at", OffsetDateTime.class).toInstant(),
                rs.getObject("capture_start", OffsetDateTime.class).toInstant(),
                rs.getObject("capture_end", OffsetDateTime.class).toInstant(),
                rs.getLong("voiced_millis"),
                rs.getBoolean("clock_skew_suspected"),
                rs.getString("failure_reason"));
    }
}
