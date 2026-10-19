package io.github.pregonhq.pregon.server.stt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.pregonhq.pregon.spi.speech.AudioSegment;
import io.github.pregonhq.pregon.spi.speech.SpeechToTextException;
import io.github.pregonhq.pregon.spi.speech.TranscriptionRequest;
import io.github.pregonhq.pregon.spi.speech.TranscriptionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The adapter against a scripted sidecar: what it sends, what it makes of every answer the contract allows. */
class SidecarSpeechToTextTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private FakeSidecar sidecar;
    private SidecarSpeechToText stt;

    @BeforeEach
    void start() throws IOException {
        sidecar = new FakeSidecar();
        stt = new SidecarSpeechToText(sidecar.baseUrl(), Duration.ofSeconds(5), 4, HttpClient.newHttpClient());
    }

    @AfterEach
    void stop() {
        sidecar.close();
    }

    @Nested
    class TheRequest {

        @Test
        void carriesTheJsonPartAndOneAudioPartNamedWithEachSegmentId() {
            AudioSegment a = segment("a-bytes");
            AudioSegment b = segment("b-bytes");
            sidecar.respond(200, FakeSidecar.json(List.of(transcribed(a.id(), "hola"), transcribed(b.id(), ""))));

            stt.transcribe(new TranscriptionRequest("es", "combo, papas grandes", List.of(a, b)));

            FakeSidecar.Received received = sidecar.lastRequest();
            assertThat(received.method()).isEqualTo("POST");
            assertThat(received.path()).isEqualTo("/v1/transcriptions");
            assertThat(received.headers().get("content-type")).startsWith("multipart/form-data; boundary=");
            assertThat(received.parts().keySet()).containsExactly("request", a.id().toString(), b.id().toString());

            FakeSidecar.Part request = received.parts().get("request");
            assertThat(request.contentType()).isEqualTo("application/json");
            Map<String, Object> json = JSON.readValue(request.data(), JSON.constructType(Map.class));
            assertThat(json).containsEntry("language", "es").containsEntry("prompt", "combo, papas grandes")
                    .containsEntry("segments", List.of(a.id().toString(), b.id().toString()));

            assertThat(received.parts().get(a.id().toString()).contentType()).isEqualTo("audio/ogg");
            assertThat(received.parts().get(a.id().toString()).data()).isEqualTo(a.audio());
            assertThat(received.parts().get(b.id().toString()).data()).isEqualTo(b.audio());
        }

        @Test
        void omitsThePromptWhenThereIsNone() {
            AudioSegment a = segment("a");
            sidecar.respond(200, FakeSidecar.json(List.of(transcribed(a.id(), "hola"))));

            stt.transcribe(new TranscriptionRequest("es", List.of(a)));

            Map<String, Object> json = JSON.readValue(sidecar.lastRequest().parts().get("request").data(), JSON.constructType(Map.class));
            assertThat(json).doesNotContainKey("prompt");
        }

        @Test
        void refusesABatchLargerThanTheLimitBeforeCallingTheSidecar() {
            List<AudioSegment> five = List.of(segment("1"), segment("2"), segment("3"), segment("4"), segment("5"));

            assertThatThrownBy(() -> stt.transcribe(new TranscriptionRequest("es", five)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("5").hasMessageContaining("4");
            assertThat(sidecar.lastRequest()).isNull();
            assertThat(stt.maxBatchSize()).isEqualTo(4);
        }
    }

    @Nested
    class TheAnswer {

        @Test
        void mapsEveryFieldOfATranscribedSegmentAndKeepsTheRequestOrder() {
            AudioSegment a = segment("a");
            AudioSegment b = segment("b");
            sidecar.respond(200, FakeSidecar.json(List.of(
                    // The sidecar may answer in any order; the port answers in the request's.
                    """
                    {"id":"%s","status":"transcribed","text":"","language":"es","duration":4.5,"segments":[],"processingMillis":80}
                    """.formatted(b.id()),
                    """
                    {"id":"%s","status":"transcribed","text":"¿Quiere papas grandes?","language":"es","duration":9.98,
                     "segments":[{"start":0.0,"end":2.5,"text":"¿Quiere papas grandes?","avgLogprob":-0.31,"noSpeechProb":0.02,"compressionRatio":1.1}],
                     "processingMillis":1234}
                    """.formatted(a.id()))));

            List<TranscriptionResult> results = stt.transcribe(new TranscriptionRequest("es", List.of(a, b)));

            assertThat(results).hasSize(2);
            TranscriptionResult.Transcribed first = (TranscriptionResult.Transcribed) results.get(0);
            assertThat(first.segmentId()).isEqualTo(a.id());
            assertThat(first.text()).isEqualTo("¿Quiere papas grandes?");
            assertThat(first.language()).isEqualTo("es");
            assertThat(first.audioDuration()).isEqualTo(Duration.ofMillis(9980));
            assertThat(first.processingTime()).isEqualTo(Duration.ofMillis(1234));
            assertThat(first.spans()).hasSize(1);
            assertThat(first.spans().get(0).start()).isEqualTo(Duration.ZERO);
            assertThat(first.spans().get(0).end()).isEqualTo(Duration.ofMillis(2500));
            assertThat(first.spans().get(0).avgLogprob()).isCloseTo(-0.31, within(1e-9));
            assertThat(first.spans().get(0).noSpeechProb()).isCloseTo(0.02, within(1e-9));
            assertThat(first.spans().get(0).compressionRatio()).isCloseTo(1.1, within(1e-9));
            assertThat(first.spans().get(0).hasConfidence()).isTrue();

            TranscriptionResult.Transcribed second = (TranscriptionResult.Transcribed) results.get(1);
            assertThat(second.segmentId()).isEqualTo(b.id());
            assertThat(second.text()).isEmpty();
            assertThat(second.spans()).isEmpty();
        }

        @Test
        void aFailedSegmentDoesNotFailTheBatch() {
            AudioSegment good = segment("good");
            AudioSegment bad = segment("bad");
            sidecar.respond(200, FakeSidecar.json(List.of(transcribed(good.id(), "hola"),
                    "{\"id\":\"%s\",\"status\":\"failed\",\"error\":\"audio could not be decoded\"}".formatted(bad.id()))));

            List<TranscriptionResult> results = stt.transcribe(new TranscriptionRequest("es", List.of(good, bad)));

            assertThat(results.get(0)).isInstanceOf(TranscriptionResult.Transcribed.class);
            assertThat(results.get(1)).isEqualTo(new TranscriptionResult.Failed(bad.id(), "audio could not be decoded"));
        }

        @Test
        void toleratesFieldsANewerSidecarMayAdd() {
            AudioSegment a = segment("a");
            sidecar.respond(200, """
                    {"model":"small","engine":"faster-whisper 2.0","results":[
                      {"id":"%s","status":"transcribed","text":"hola","language":"es","duration":1.0,"segments":[],
                       "processingMillis":5,"words":[{"w":"hola"}]}]}
                    """.formatted(a.id()));

            assertThat(stt.transcribe(new TranscriptionRequest("es", List.of(a)))).hasSize(1);
        }

        @Test
        void aMissingOrUnexpectedResultBreaksTheContract() {
            AudioSegment a = segment("a");
            AudioSegment b = segment("b");
            UUID stranger = UUID.randomUUID();

            sidecar.respond(200, FakeSidecar.json(List.of(transcribed(a.id(), "hola"))));
            assertThatThrownBy(() -> stt.transcribe(new TranscriptionRequest("es", List.of(a, b))))
                    .isInstanceOf(SpeechToTextException.class).hasMessageContaining(b.id().toString())
                    .extracting(e -> ((SpeechToTextException) e).isRetryable()).isEqualTo(false);

            sidecar.respond(200, FakeSidecar.json(List.of(transcribed(a.id(), "hola"), transcribed(stranger, "x"))));
            assertThatThrownBy(() -> stt.transcribe(new TranscriptionRequest("es", List.of(a))))
                    .isInstanceOf(SpeechToTextException.class).hasMessageContaining(stranger.toString());

            sidecar.respond(200, FakeSidecar.json(List.of("{\"id\":\"%s\",\"status\":\"maybe\"}".formatted(a.id()))));
            assertThatThrownBy(() -> stt.transcribe(new TranscriptionRequest("es", List.of(a))))
                    .isInstanceOf(SpeechToTextException.class).hasMessageContaining("maybe");

            sidecar.respond(200, "not json at all");
            assertThatThrownBy(() -> stt.transcribe(new TranscriptionRequest("es", List.of(a))))
                    .isInstanceOf(SpeechToTextException.class).hasMessageContaining("not a TranscriptionResponse");
        }
    }

    @Nested
    class TheRefusals {

        @Test
        void notReadyIsRetryableAfterWhatTheSidecarAsks() {
            sidecar.respond(503, Map.of("Retry-After", "5"), problem(503, "loading model"));

            assertThatThrownBy(() -> stt.transcribe(request()))
                    .isInstanceOfSatisfying(SpeechToTextException.class, e -> {
                        assertThat(e.isRetryable()).isTrue();
                        assertThat(e.retryAfter()).contains(Duration.ofSeconds(5));
                        assertThat(e.getMessage()).contains("loading model");
                    });
        }

        @Test
        void notReadyWithoutAUsableRetryAfterIsStillRetryable() {
            sidecar.respond(503, Map.of("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT"), problem(503, "loading model"));

            assertThatThrownBy(() -> stt.transcribe(request()))
                    .isInstanceOfSatisfying(SpeechToTextException.class, e -> {
                        assertThat(e.isRetryable()).isTrue();
                        assertThat(e.retryAfter()).isEmpty();
                    });
        }

        @Test
        void aRejectedBatchIsOurBugAndIsNotRetried() {
            sidecar.respond(400, problem(400, "segments without an audio part: x"));

            assertThatThrownBy(() -> stt.transcribe(request()))
                    .isInstanceOfSatisfying(SpeechToTextException.class, e -> {
                        assertThat(e.isRetryable()).isFalse();
                        assertThat(e.getMessage()).contains("400").contains("segments without an audio part: x");
                    });

            sidecar.respond(413, problem(413, "part 'x' is larger than 1048576 bytes"));
            assertThatThrownBy(() -> stt.transcribe(request()))
                    .isInstanceOfSatisfying(SpeechToTextException.class, e -> assertThat(e.isRetryable()).isFalse());
        }

        @Test
        void aServerErrorIsRetryableWithoutADelay() {
            sidecar.respond(500, Map.of("Content-Type", "text/html"), "<html>Internal Server Error</html>");

            assertThatThrownBy(() -> stt.transcribe(request()))
                    .isInstanceOfSatisfying(SpeechToTextException.class, e -> {
                        assertThat(e.isRetryable()).isTrue();
                        assertThat(e.retryAfter()).isEmpty();
                        assertThat(e.getMessage()).contains("500").contains("Internal Server Error");
                    });
        }

        @Test
        void anUnreachableSidecarIsRetryable() {
            sidecar.close();
            SidecarSpeechToText unreachable = new SidecarSpeechToText(sidecar.baseUrl(), Duration.ofSeconds(5), 4, HttpClient.newHttpClient());

            assertThatThrownBy(() -> unreachable.transcribe(request()))
                    .isInstanceOfSatisfying(SpeechToTextException.class, e -> {
                        assertThat(e.isRetryable()).isTrue();
                        assertThat(e.getMessage()).contains("unreachable");
                    });
        }

        @Test
        void aSidecarThatTakesTooLongIsRetryable() {
            sidecar.respondWith(received -> {
                try {
                    Thread.sleep(700);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new FakeSidecar.Scripted(200, Map.of(), "{}");
            });
            SidecarSpeechToText impatient = new SidecarSpeechToText(sidecar.baseUrl(), Duration.ofMillis(200), 4, HttpClient.newHttpClient());

            assertThatThrownBy(() -> impatient.transcribe(request()))
                    .isInstanceOfSatisfying(SpeechToTextException.class, e -> {
                        assertThat(e.isRetryable()).isTrue();
                        assertThat(e.getMessage()).contains("did not answer within");
                    });
        }
    }

    @Test
    void theBaseUrlMayOrMayNotEndWithASlash() {
        AudioSegment a = segment("a");
        sidecar.respond(200, FakeSidecar.json(List.of(transcribed(a.id(), "hola"))));
        String bare = sidecar.baseUrl().toString().replaceAll("/$", "");

        new SidecarSpeechToText(URI.create(bare), Duration.ofSeconds(5), 4, HttpClient.newHttpClient())
                .transcribe(new TranscriptionRequest("es", List.of(a)));

        assertThat(sidecar.lastRequest().path()).isEqualTo("/v1/transcriptions");
    }

    private static TranscriptionRequest request() {
        return new TranscriptionRequest("es", List.of(segment("a")));
    }

    private static AudioSegment segment(String bytes) {
        return new AudioSegment(UUID.randomUUID(), bytes.getBytes(StandardCharsets.UTF_8));
    }

    private static String transcribed(UUID id, String text) {
        return "{\"id\":\"%s\",\"status\":\"transcribed\",\"text\":\"%s\",\"language\":\"es\",\"duration\":1.0,\"segments\":[],\"processingMillis\":1}"
                .formatted(id, text);
    }

    private static String problem(int status, String detail) {
        return "{\"type\":\"about:blank\",\"title\":\"x\",\"status\":%d,\"detail\":\"%s\"}".formatted(status, detail);
    }
}
