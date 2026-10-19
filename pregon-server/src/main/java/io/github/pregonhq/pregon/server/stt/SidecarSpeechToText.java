package io.github.pregonhq.pregon.server.stt;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.pregonhq.pregon.spi.speech.AudioSegment;
import io.github.pregonhq.pregon.spi.speech.SpeechSpan;
import io.github.pregonhq.pregon.spi.speech.SpeechToText;
import io.github.pregonhq.pregon.spi.speech.SpeechToTextException;
import io.github.pregonhq.pregon.spi.speech.TranscriptionRequest;
import io.github.pregonhq.pregon.spi.speech.TranscriptionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The speech-to-text port implemented over Pregón's own sidecar ({@code stt-sidecar/openapi.yaml},
 * ADR-0015): one {@code POST /v1/transcriptions} per batch, multipart, with a {@code request} JSON
 * part and one audio part named with each segment id.
 *
 * <p>Audio goes out as the byte arrays it arrived in, never copied or written anywhere, and only
 * ids, sizes and timings are logged (ADR-0006). The sidecar's answer is checked against the
 * contract: a result for every requested id, nothing else.
 */
public final class SidecarSpeechToText implements SpeechToText {

    static final String TRANSCRIPTIONS_PATH = "/v1/transcriptions";
    private static final Logger log = LoggerFactory.getLogger(SidecarSpeechToText.class);

    private final URI transcriptions;
    private final Duration timeout;
    private final int maxBatchSize;
    private final HttpClient http;
    // Its own mapper: a newer sidecar may add fields, and the server's mapper rejects unknown ones.
    private final JsonMapper json = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    /**
     * @param baseUrl where the sidecar listens, e.g. {@code http://stt:8000}
     * @param timeout how long one batch may take end to end
     * @param maxBatchSize segments per request; must not exceed the sidecar's {@code PREGON_STT_MAX_BATCH}
     */
    public SidecarSpeechToText(URI baseUrl, Duration timeout, int maxBatchSize, HttpClient http) {
        if (maxBatchSize < 1) {
            throw new IllegalArgumentException("maxBatchSize must be at least 1, got " + maxBatchSize);
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive, got " + timeout);
        }
        this.transcriptions = baseUrl.resolve(TRANSCRIPTIONS_PATH);
        this.timeout = timeout;
        this.maxBatchSize = maxBatchSize;
        this.http = http;
    }

    @Override
    public int maxBatchSize() {
        return maxBatchSize;
    }

    @Override
    public List<TranscriptionResult> transcribe(TranscriptionRequest request) {
        if (request.segments().size() > maxBatchSize) {
            throw new IllegalArgumentException("batch of " + request.segments().size() + " segments exceeds the limit of " + maxBatchSize);
        }
        String boundary = "pregon-" + UUID.randomUUID();
        long audioBytes = request.segments().stream().mapToLong(s -> s.audio().length).sum();
        HttpRequest post = HttpRequest.newBuilder(transcriptions)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofByteArrays(body(boundary, request)))
                .build();

        long started = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = http.send(post, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw SpeechToTextException.retryable("sidecar did not answer within " + timeout, null, e);
        } catch (IOException e) {
            throw SpeechToTextException.retryable("sidecar unreachable at " + transcriptions + ": " + e.getMessage(), null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw SpeechToTextException.retryable("interrupted while waiting for the sidecar", null, e);
        }
        long millis = (System.nanoTime() - started) / 1_000_000;

        if (response.statusCode() != 200) {
            throw failure(response);
        }
        List<TranscriptionResult> results = results(request, response.body());
        log.debug("transcribed {} segments ({} bytes) in {} ms", request.segments().size(), audioBytes, millis);
        return results;
    }

    private List<byte[]> body(String boundary, TranscriptionRequest request) {
        List<byte[]> chunks = new ArrayList<>();
        chunks.add(part(boundary, "request", null, "application/json"));
        chunks.add(json.writeValueAsBytes(new Request(request.language(), request.prompt().isEmpty() ? null : request.prompt(),
                request.segments().stream().map(AudioSegment::id).toList())));
        for (AudioSegment segment : request.segments()) {
            chunks.add(part(boundary, segment.id().toString(), segment.id() + ".ogg", "audio/ogg"));
            chunks.add(segment.audio());
        }
        chunks.add(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return chunks;
    }

    private static byte[] part(String boundary, String name, String filename, String contentType) {
        StringBuilder header = new StringBuilder();
        header.append("\r\n--").append(boundary).append("\r\n");
        header.append("Content-Disposition: form-data; name=\"").append(name).append('"');
        if (filename != null) {
            header.append("; filename=\"").append(filename).append('"');
        }
        header.append("\r\nContent-Type: ").append(contentType).append("\r\n\r\n");
        return header.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private List<TranscriptionResult> results(TranscriptionRequest request, String body) {
        Response response;
        try {
            response = json.readValue(body, Response.class);
        } catch (RuntimeException e) {
            throw SpeechToTextException.permanent("sidecar answered 200 with a body that is not a TranscriptionResponse: " + e.getMessage());
        }
        Map<UUID, Result> byId = new HashMap<>();
        for (Result result : response.results() == null ? List.<Result>of() : response.results()) {
            if (result.id() == null || result.status() == null || byId.put(result.id(), result) != null) {
                throw SpeechToTextException.permanent("sidecar broke the contract: a result without id or status, or a repeated id");
            }
        }
        List<TranscriptionResult> results = new ArrayList<>(request.segments().size());
        for (AudioSegment segment : request.segments()) {
            Result result = byId.remove(segment.id());
            if (result == null) {
                throw SpeechToTextException.permanent("sidecar broke the contract: no result for segment " + segment.id());
            }
            results.add(toResult(result));
        }
        if (!byId.isEmpty()) {
            throw SpeechToTextException.permanent("sidecar broke the contract: results for segments that were not sent: " + byId.keySet());
        }
        return results;
    }

    private static TranscriptionResult toResult(Result result) {
        return switch (result.status()) {
            case "transcribed" -> new TranscriptionResult.Transcribed(
                    result.id(),
                    result.text() == null ? "" : result.text(),
                    result.language() == null ? "" : result.language(),
                    seconds(result.duration()),
                    (result.segments() == null ? List.<Span>of() : result.segments()).stream()
                            .map(s -> new SpeechSpan(seconds(s.start()), seconds(s.end()), s.text() == null ? "" : s.text(),
                                    orNaN(s.avgLogprob()), orNaN(s.noSpeechProb()), orNaN(s.compressionRatio())))
                            .toList(),
                    Duration.ofMillis(result.processingMillis() == null ? 0 : result.processingMillis()));
            case "failed" -> new TranscriptionResult.Failed(result.id(), result.error() == null ? "no reason given" : result.error());
            default -> throw SpeechToTextException.permanent("sidecar broke the contract: unknown status '" + result.status() + "'");
        };
    }

    private static Duration seconds(Double value) {
        return value == null ? Duration.ZERO : Duration.ofNanos(Math.round(value * 1_000_000_000d));
    }

    private static double orNaN(Double value) {
        return value == null ? Double.NaN : value;
    }

    /** Maps the sidecar's refusal to the port's two kinds of failure, using its problem+json when it has one. */
    private SpeechToTextException failure(HttpResponse<String> response) {
        int status = response.statusCode();
        String detail = detail(response.body());
        if (status == 503) {
            Duration retryAfter = response.headers().firstValue("Retry-After")
                    .map(SidecarSpeechToText::retryAfterSeconds).orElse(null);
            return SpeechToTextException.retryable("sidecar not ready: " + detail, retryAfter, null);
        }
        if (status >= 500) {
            return SpeechToTextException.retryable("sidecar failed with " + status + ": " + detail, null, null);
        }
        return SpeechToTextException.permanent("sidecar rejected the batch with " + status + ": " + detail);
    }

    private String detail(String body) {
        if (body == null || body.isBlank()) {
            return "no detail";
        }
        try {
            Problem problem = json.readValue(body, Problem.class);
            if (problem.detail() != null && !problem.detail().isBlank()) {
                return problem.detail();
            }
        } catch (RuntimeException notAProblem) {
            // Fall through: the body is whatever the sidecar or a proxy in front of it wrote.
        }
        return body.length() > 200 ? body.substring(0, 200) : body;
    }

    private static Duration retryAfterSeconds(String header) {
        try {
            long seconds = Long.parseLong(header.strip());
            return seconds > 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException e) {
            return null; // An HTTP-date is allowed by HTTP but not by the sidecar's contract.
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Request(String language, String prompt, List<UUID> segments) {
    }

    record Response(String model, List<Result> results) {
    }

    record Result(UUID id, String status, String text, String language, Double duration, List<Span> segments,
            Long processingMillis, String error) {
    }

    record Span(Double start, Double end, String text, Double avgLogprob, Double noSpeechProb, Double compressionRatio) {
    }

    record Problem(String detail) {
    }
}
