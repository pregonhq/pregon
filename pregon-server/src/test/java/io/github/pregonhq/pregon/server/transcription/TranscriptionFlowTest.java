package io.github.pregonhq.pregon.server.transcription;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.github.pregonhq.pregon.server.ingest.IngestTestSupport;
import io.github.pregonhq.pregon.server.ingest.OggOpus;
import io.github.pregonhq.pregon.server.stt.FakeSidecar;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The server end to end with a sidecar that answers what the test scripts: a segment is uploaded,
 * the worker transcribes it, the transcript is readable through the Admin API and the audio is
 * gone (ADR-0024, ADR-0027). The context is closed after the class so that its worker does not
 * drain the queues of the tests that assume nothing does.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TranscriptionFlowTest extends IngestTestSupport {

    static final FakeSidecar SIDECAR = start();
    static final Duration PATIENCE = Duration.ofSeconds(15);

    @DynamicPropertySource
    static void sidecar(DynamicPropertyRegistry registry) {
        registry.add("PREGON_STT_URL", () -> SIDECAR.baseUrl().toString());
        registry.add("PREGON_STT_POLL", () -> "PT0.2S");
        registry.add("PREGON_RETENTION_TRANSCRIPTS", () -> "P7D");
    }

    @AfterAll
    static void stopSidecar() {
        SIDECAR.close();
    }

    @Test
    void anUploadedSegmentIsTranscribedItsTextIsReadableAndItsAudioIsGone() throws Exception {
        Tenant t = newTenant();
        SIDECAR.respondWith(transcribing(id -> """
                {"id":"%s","status":"transcribed","text":"¿Quiere papas grandes?","language":"es","duration":9.98,
                 "segments":[{"start":0.5,"end":2.5,"text":"¿Quiere papas grandes?","avgLogprob":-0.31,"noSpeechProb":0.02,"compressionRatio":1.1}],
                 "processingMillis":1234}
                """.formatted(id)));
        String segmentId = segmentId();
        HttpResponse<String> upload = putSegment(t.ingestKey(), segmentId, metadata(t.sourceId()), OggOpus.silence(500, 1));
        assertThat(upload.statusCode()).as(upload.body()).isEqualTo(202);

        HttpResponse<String> status = getUntil(t.adminKey(), segmentId, "TRANSCRIBED");

        assertThat(status.statusCode()).as(status.body()).isEqualTo(200);
        assertThat(json(status, "state")).isEqualTo("TRANSCRIBED");
        assertThat(json(status, "attempt")).isEqualTo("1");
        assertThat(json(status, "sourceId")).isEqualTo(t.sourceId());
        assertThat(json(status, "voicedDuration")).isEqualTo("PT6.4S");
        assertThat(json(status, "transcript.text")).isEqualTo("¿Quiere papas grandes?");
        assertThat(json(status, "transcript.language")).isEqualTo("es");
        assertThat(json(status, "transcript.audioDuration")).isEqualTo("PT9.98S");
        assertThat(json(status, "transcript.spans[0].start")).isEqualTo("PT0.5S");
        assertThat(json(status, "transcript.spans[0].noSpeechProb")).isEqualTo("0.02");
        assertThat(status.body()).doesNotContain("failureReason");
        assertThat(jdbc.sql("SELECT count(*) FROM segment_audio WHERE segment_id = :id::uuid").param("id", segmentId)
                .query(Long.class).single()).isZero();
    }

    @Test
    void aSegmentTheSidecarCannotDecodeEndsFailedAfterTheLastAttemptWithItsReason() throws Exception {
        Tenant t = newTenant();
        SIDECAR.respondWith(transcribing(id -> "{\"id\":\"%s\",\"status\":\"failed\",\"error\":\"audio could not be decoded\"}".formatted(id)));
        String segmentId = segmentId();
        assertThat(putSegment(t.ingestKey(), segmentId, metadata(t.sourceId()), OggOpus.silence(500, 2)).statusCode()).isEqualTo(202);

        HttpResponse<String> status = getUntil(t.adminKey(), segmentId, "FAILED");

        assertThat(json(status, "state")).isEqualTo("FAILED");
        assertThat(json(status, "attempt")).isEqualTo("3");
        assertThat(json(status, "failureReason")).isEqualTo("audio could not be decoded");
        assertThat(status.body()).doesNotContain("\"transcript\"");
        assertThat(jdbc.sql("SELECT count(*) FROM segment_audio WHERE segment_id = :id::uuid").param("id", segmentId)
                .query(Long.class).single()).isZero();
    }

    @Test
    void theStatusNeedsAnAdminKeyAndNeverRevealsAnotherTenantsSegment() throws Exception {
        Tenant t = newTenant();
        Tenant other = newTenant();
        SIDECAR.respondWith(transcribing(id -> "{\"id\":\"%s\",\"status\":\"transcribed\",\"text\":\"\",\"language\":\"es\",\"duration\":1.0,\"segments\":[],\"processingMillis\":1}".formatted(id)));
        String segmentId = segmentId();
        assertThat(putSegment(t.ingestKey(), segmentId, metadata(t.sourceId()), OggOpus.silence(500, 3)).statusCode()).isEqualTo(202);

        assertThat(get(t.ingestKey(), segmentId).statusCode()).isEqualTo(403);
        assertProblem(get(other.adminKey(), segmentId), 404);
        assertProblem(get(t.adminKey(), "not-a-uuid"), 404);
        assertProblem(get(t.adminKey(), segmentId()), 404);
        assertThat(get(t.adminKey(), segmentId).statusCode()).isEqualTo(200);
    }

    /** Answers every batch with one scripted result per segment id it carries. */
    private static Function<FakeSidecar.Received, FakeSidecar.Scripted> transcribing(Function<String, String> result) {
        return received -> {
            List<String> results = new ArrayList<>();
            for (String name : received.parts().keySet()) {
                if (!"request".equals(name)) {
                    results.add(result.apply(name));
                }
            }
            return new FakeSidecar.Scripted(200, Map.of("Content-Type", "application/json"), FakeSidecar.json(results));
        };
    }

    private HttpResponse<String> getUntil(String adminKey, String segmentId, String state) throws Exception {
        Instant deadline = Instant.now().plus(PATIENCE);
        HttpResponse<String> response;
        do {
            response = get(adminKey, segmentId);
            if (response.statusCode() == 200 && state.equals(json(response, "state"))) {
                return response;
            }
            Thread.sleep(100);
        } while (Instant.now().isBefore(deadline));
        return response;
    }

    private HttpResponse<String> get(String key, String segmentId) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/v1/segments/" + segmentId)).header("Authorization", "Bearer " + key).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static FakeSidecar start() {
        try {
            return new FakeSidecar();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
