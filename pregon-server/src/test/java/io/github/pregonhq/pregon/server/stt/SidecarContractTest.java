package io.github.pregonhq.pregon.server.stt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import io.github.pregonhq.pregon.spi.speech.AudioSegment;
import io.github.pregonhq.pregon.spi.speech.SpeechToText;
import io.github.pregonhq.pregon.spi.speech.SpeechToTextException;
import io.github.pregonhq.pregon.spi.speech.TranscriptionRequest;
import io.github.pregonhq.pregon.spi.speech.TranscriptionResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The adapter against the real sidecar, built from {@code stt-sidecar/} with the {@code tiny}
 * model: the two sides of {@code stt-sidecar/openapi.yaml} meet here, so a change in either one
 * that the fake would not notice (a renamed field, a new status) fails this test.
 *
 * <p>The audio is the synthetic Ogg/Opus of the sidecar's own tests
 * ({@code stt-sidecar/tests/oggopus.py}, {@code silence(500, 1)}): 10 s of silence, no recorded
 * sound. Set {@code PREGON_STT_TEST_MODEL_CACHE} to a directory to keep the model between runs.
 */
@Testcontainers
class SidecarContractTest {

    @Container
    static final GenericContainer<?> SIDECAR = sidecar();

    static SpeechToText stt;
    static SttHealthIndicator health;
    static byte[] silence;

    private static GenericContainer<?> sidecar() {
        GenericContainer<?> container = new GenericContainer<>(new ImageFromDockerfile("pregon-stt-contract", false)
                .withFileFromPath(".", Paths.get("..", "stt-sidecar")))
                .withEnv("PREGON_STT_MODEL", "tiny")
                .withEnv("PREGON_STT_MAX_BATCH", "2")
                .withExposedPorts(8000)
                .waitingFor(Wait.forHttp(SttHealthIndicator.HEALTH_PATH).forPort(8000).forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(5)));
        String cache = System.getenv("PREGON_STT_TEST_MODEL_CACHE");
        if (cache != null && !cache.isBlank()) {
            container.withFileSystemBind(cache, "/models", BindMode.READ_WRITE);
        }
        return container;
    }

    @BeforeAll
    static void connect() throws IOException {
        URI baseUrl = URI.create("http://" + SIDECAR.getHost() + ":" + SIDECAR.getMappedPort(8000) + "/");
        HttpClient http = HttpClient.newHttpClient();
        stt = new SidecarSpeechToText(baseUrl, Duration.ofMinutes(2), 64, http);
        health = new SttHealthIndicator(baseUrl, http);
        silence = Files.readAllBytes(Path.of("src/test/resources/audio/synthetic/silence-10s.ogg"));
    }

    @Test
    void theHealthComponentSeesTheLoadedModel() {
        var result = health.health();

        assertThat(result.getStatus()).isEqualTo(Status.UP);
        assertThat(result.getDetails()).containsEntry("model", "tiny").containsEntry("device", "cpu");
    }

    @Test
    void transcribesRealOggOpusAndReportsItsDuration() {
        UUID id = UUID.randomUUID();

        List<TranscriptionResult> results = stt.transcribe(
                new TranscriptionRequest("es", "combo, papas grandes", List.of(new AudioSegment(id, silence))));

        assertThat(results).hasSize(1);
        TranscriptionResult.Transcribed transcribed = (TranscriptionResult.Transcribed) results.get(0);
        assertThat(transcribed.segmentId()).isEqualTo(id);
        assertThat(transcribed.language()).isEqualTo("es");
        assertThat(transcribed.audioDuration().toMillis()).isBetween(9_900L, 10_100L);
        assertThat(transcribed.processingTime()).isPositive();
        // Whatever `tiny` hallucinates on silence, every span is inside Whisper's 30 s window and carries its confidence.
        for (var span : transcribed.spans()) {
            assertThat(span.start()).isBetween(Duration.ZERO, span.end());
            assertThat(span.end()).isLessThanOrEqualTo(Duration.ofSeconds(30));
            assertThat(span.hasConfidence()).isTrue();
        }
    }

    @Test
    void undecodableAudioFailsItsSegmentOnly() {
        AudioSegment good = new AudioSegment(UUID.randomUUID(), silence);
        AudioSegment bad = new AudioSegment(UUID.randomUUID(), "not audio".getBytes(StandardCharsets.UTF_8));

        List<TranscriptionResult> results = stt.transcribe(new TranscriptionRequest("es", List.of(good, bad)));

        assertThat(results.get(0)).isInstanceOf(TranscriptionResult.Transcribed.class);
        assertThat(results.get(1)).isInstanceOfSatisfying(TranscriptionResult.Failed.class, failed -> {
            assertThat(failed.segmentId()).isEqualTo(bad.id());
            assertThat(failed.reason()).isNotBlank();
        });
    }

    @Test
    void aBatchTheSidecarRefusesIsAPermanentFailureWithItsReason() {
        List<AudioSegment> three = List.of(new AudioSegment(UUID.randomUUID(), silence),
                new AudioSegment(UUID.randomUUID(), silence), new AudioSegment(UUID.randomUUID(), silence));

        assertThatThrownBy(() -> stt.transcribe(new TranscriptionRequest("es", three)))
                .isInstanceOfSatisfying(SpeechToTextException.class, e -> {
                    assertThat(e.isRetryable()).isFalse();
                    assertThat(e.getMessage()).contains("400").contains("at most 2 segments");
                });
    }
}
