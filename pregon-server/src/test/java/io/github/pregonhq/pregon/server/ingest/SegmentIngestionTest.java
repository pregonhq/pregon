package io.github.pregonhq.pregon.server.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The behavior of {@code PUT /v1/segments/{segmentId}} fixed by ADR-0024, seen only through the
 * contract. Queue internals (leases, attempts, a crash mid-transaction) are tested against the
 * queue itself, not here.
 */
class SegmentIngestionTest extends IngestTestSupport {

    /** {@code PREGON_INGEST_MAX_SEGMENT_BYTES} default (docs/configuration.md). */
    static final int MAX_SEGMENT_BYTES = 1_048_576;

    @Nested
    class Idempotency {

        @Test
        void theFirstUploadIsAcceptedWithAReceipt() throws Exception {
            Tenant t = newTenant();
            String id = segmentId();
            Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);

            HttpResponse<String> response = putSegment(t.ingestKey(), id, metadata(t.sourceId()), OggOpus.silence(500, 0));

            assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
            assertThat(json(response, "$.segmentId")).isEqualTo(id);
            assertThat(Instant.parse(json(response, "$.receivedAt"))).isBetween(before.minusSeconds(1), Instant.now());
            assertThat(json(response, "$.clockSkewSuspected")).isEqualTo("false");
        }

        @Test
        void aRetryWithTheSameContentReturnsTheOriginalReceipt() throws Exception {
            Tenant t = newTenant();
            String id = segmentId();
            String metadata = metadata(t.sourceId());
            byte[] audio = OggOpus.silence(500, 0);

            HttpResponse<String> first = putSegment(t.ingestKey(), id, metadata, audio);
            HttpResponse<String> retry = putSegment(t.ingestKey(), id, metadata, audio);

            assertThat(first.statusCode()).isEqualTo(202);
            assertThat(retry.statusCode()).as(retry.body()).isEqualTo(200);
            assertThat(json(retry, "$.receivedAt")).isEqualTo(json(first, "$.receivedAt"));
            assertThat(json(retry, "$.segmentId")).isEqualTo(id);
        }

        @Test
        void theSameMetadataWithItsFieldsInAnotherOrderIsTheSameContent() throws Exception {
            // A rewritten agent, or another JSON library, must not turn a safe retry into a 409.
            Tenant t = newTenant();
            String id = segmentId();
            Instant end = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            Instant start = end.minusSeconds(10);
            byte[] audio = OggOpus.silence(500, 0);

            putSegment(t.ingestKey(), id, metadata(t.sourceId(), start, end), audio);
            HttpResponse<String> retry = putSegment(t.ingestKey(), id, """
                    {"agentVersion":"test","voicedDuration":"PT6.4S","captureEnd":"%s",
                     "captureStart":"%s","sourceId":"%s"}""".formatted(end, start, t.sourceId()), audio);

            assertThat(retry.statusCode()).as(retry.body()).isEqualTo(200);
        }

        @Test
        void reusingAnIdWithOtherAudioIsAConflict() throws Exception {
            Tenant t = newTenant();
            String id = segmentId();
            String metadata = metadata(t.sourceId());

            putSegment(t.ingestKey(), id, metadata, OggOpus.silence(500, 0));

            assertProblem(putSegment(t.ingestKey(), id, metadata, OggOpus.silence(500, 1)), 409);
        }

        @Test
        void reusingAnIdWithOtherMetadataIsAConflict() throws Exception {
            Tenant t = newTenant();
            String id = segmentId();
            Instant end = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            byte[] audio = OggOpus.silence(500, 0);

            putSegment(t.ingestKey(), id, metadata(t.sourceId(), end.minusSeconds(10), end), audio);

            assertProblem(putSegment(t.ingestKey(), id, metadata(t.sourceId(), end.minusSeconds(11), end), audio), 409);
            assertProblem(putSegment(t.ingestKey(), id, metadata(newSource(t.adminKey()), end.minusSeconds(10), end), audio), 409);
        }

        @Test
        void aConflictLeavesTheOriginalSegmentUntouched() throws Exception {
            Tenant t = newTenant();
            String id = segmentId();
            String metadata = metadata(t.sourceId());
            byte[] audio = OggOpus.silence(500, 0);

            HttpResponse<String> first = putSegment(t.ingestKey(), id, metadata, audio);
            putSegment(t.ingestKey(), id, metadata, OggOpus.silence(500, 1));
            HttpResponse<String> retry = putSegment(t.ingestKey(), id, metadata, audio);

            assertThat(retry.statusCode()).isEqualTo(200);
            assertThat(json(retry, "$.receivedAt")).isEqualTo(json(first, "$.receivedAt"));
        }

        @Test
        void simultaneousUploadsOfTheSameSegmentAreAcceptedExactlyOnce() throws Exception {
            Tenant t = newTenant();
            String id = segmentId();
            byte[] body = multipart(metadata(t.sourceId()), OggOpus.silence(500, 0));
            int uploads = 10;
            CountDownLatch start = new CountDownLatch(1);
            List<Callable<HttpResponse<String>>> tasks = new ArrayList<>();
            for (int i = 0; i < uploads; i++) {
                tasks.add(() -> {
                    start.await();
                    return putSegment(t.ingestKey(), id, body);
                });
            }

            List<HttpResponse<String>> responses = new ArrayList<>();
            try (ExecutorService pool = Executors.newFixedThreadPool(uploads)) {
                List<Future<HttpResponse<String>>> futures = tasks.stream().map(pool::submit).toList();
                start.countDown();
                for (Future<HttpResponse<String>> future : futures) {
                    responses.add(future.get());
                }
            }

            assertThat(responses).extracting(HttpResponse::statusCode).as(responses.toString())
                    .containsOnly(200, 202).filteredOn(status -> status == 202).hasSize(1);
            assertThat(responses).extracting(r -> json(r, "$.receivedAt")).containsOnly(json(responses.get(0), "$.receivedAt"));
        }

        @Test
        void aSegmentCommittedBeforeTheConnectionDroppedIsNotAcceptedTwice() throws Exception {
            // The agent sends everything, the server commits and starts answering, and the network
            // fails before the agent reads the receipt: the agent retries what the server already has.
            Tenant t = newTenant();
            String id = segmentId();
            byte[] body = multipart(metadata(t.sourceId()), OggOpus.silence(500, 0));
            Instant sent = Instant.now();

            try (Socket socket = new Socket("localhost", port)) {
                OutputStream out = socket.getOutputStream();
                out.write(("PUT /v1/segments/" + id + " HTTP/1.1\r\n"
                        + "Host: localhost:" + port + "\r\n"
                        + "Authorization: Bearer " + t.ingestKey() + "\r\n"
                        + "Content-Type: multipart/form-data; boundary=" + BOUNDARY + "\r\n"
                        + "Content-Length: " + body.length + "\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
                InputStream in = socket.getInputStream();
                assertThat(in.read()).as("the server started answering").isNotEqualTo(-1);
            }

            HttpResponse<String> retry = putSegment(t.ingestKey(), id, body);

            assertThat(retry.statusCode()).as(retry.body()).isEqualTo(200);
            assertThat(Instant.parse(json(retry, "$.receivedAt"))).isBefore(Instant.now()).isAfter(sent.minusSeconds(1));
        }

        @Test
        void segmentIdsBelongToTheirTenant() throws Exception {
            // Answering 409 to another tenant would tell it that the id exists somewhere.
            Tenant mine = newTenant();
            Tenant theirs = newTenant();
            String id = segmentId();

            putSegment(theirs.ingestKey(), id, metadata(theirs.sourceId()), OggOpus.silence(500, 0));

            assertThat(putSegment(mine.ingestKey(), id, metadata(mine.sourceId()), OggOpus.silence(500, 1)).statusCode())
                    .isEqualTo(202);
        }
    }

    @Nested
    class Sources {

        @Test
        void anUnknownSourceIsNotFound() throws Exception {
            Tenant t = newTenant();
            assertUploadsAreServed(t);

            assertProblem(putSegment(t.ingestKey(), segmentId(), metadata(UUID.randomUUID().toString()),
                    OggOpus.silence(500, 0)), 404);
        }

        @Test
        void anotherTenantsSourceLooksExactlyLikeAnUnknownOne() throws Exception {
            Tenant mine = newTenant();
            Tenant theirs = newTenant();
            assertUploadsAreServed(mine);

            HttpResponse<String> unknown = putSegment(mine.ingestKey(), segmentId(),
                    metadata(UUID.randomUUID().toString()), OggOpus.silence(500, 0));
            HttpResponse<String> foreign = putSegment(mine.ingestKey(), segmentId(),
                    metadata(theirs.sourceId()), OggOpus.silence(500, 0));

            assertProblem(foreign, 404);
            assertThat(json(foreign, "$.title")).isEqualTo(json(unknown, "$.title"));
            assertThat(foreign.body()).doesNotContain(theirs.sourceId());
        }

        @Test
        void anArchivedSourceAcceptsNoSegments() throws Exception {
            Tenant t = newTenant();
            archiveSource(t.adminKey(), t.sourceId());

            assertProblem(putSegment(t.ingestKey(), segmentId(), metadata(t.sourceId()), OggOpus.silence(500, 0)), 409);
        }

        @Test
        void aRejectedSegmentLeavesNothingBehind() throws Exception {
            // Validation happens before anything is stored: the same id later is a first upload.
            Tenant t = newTenant();
            String id = segmentId();

            putSegment(t.ingestKey(), id, metadata(UUID.randomUUID().toString()), OggOpus.silence(500, 0));
            putSegment(t.ingestKey(), id, metadata(t.sourceId()), OggOpus.wav());

            assertThat(putSegment(t.ingestKey(), id, metadata(t.sourceId()), OggOpus.silence(500, 0)).statusCode())
                    .isEqualTo(202);
        }
    }

    @Nested
    class Audio {

        @Test
        void audioThatIsNotOggIsRejected() throws Exception {
            Tenant t = newTenant();

            assertProblem(putSegment(t.ingestKey(), segmentId(), metadata(t.sourceId()), OggOpus.wav()), 400);
            assertProblem(putSegment(t.ingestKey(), segmentId(), metadata(t.sourceId()),
                    "not audio at all".getBytes(StandardCharsets.US_ASCII)), 400);
            assertProblem(putSegment(t.ingestKey(), segmentId(), metadata(t.sourceId()), new byte[0]), 400);
        }

        @Test
        void oggThatDoesNotCarryOpusIsRejected() throws Exception {
            Tenant t = newTenant();

            assertProblem(putSegment(t.ingestKey(), segmentId(), metadata(t.sourceId()), OggOpus.oggWithoutOpusHead()), 400);
        }

        @Test
        void aSegmentOfExactlyTheLimitIsAccepted() throws Exception {
            Tenant t = newTenant();

            HttpResponse<String> response = putSegment(t.ingestKey(), segmentId(), metadata(t.sourceId()),
                    OggOpus.ofSize(MAX_SEGMENT_BYTES));

            assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        }

        @Test
        void aSegmentOneByteOverTheLimitIsTooLarge() throws Exception {
            Tenant t = newTenant();
            assertUploadsAreServed(t);

            assertProblem(putSegment(t.ingestKey(), segmentId(), metadata(t.sourceId()),
                    OggOpus.ofSize(MAX_SEGMENT_BYTES + 1)), 413);
        }
    }

    @Nested
    class Request {

        @Test
        void theSegmentIdMustBeAUuid() throws Exception {
            Tenant t = newTenant();

            assertProblem(putSegment(t.ingestKey(), "segment-1", metadata(t.sourceId()), OggOpus.silence(500, 0)), 400);
        }

        @Test
        void bothPartsAreRequired() throws Exception {
            Tenant t = newTenant();

            assertProblem(putSegment(t.ingestKey(), segmentId(), null, OggOpus.silence(500, 0)), 400);
            assertProblem(putSegment(t.ingestKey(), segmentId(), metadata(t.sourceId()), null), 400);
        }

        @Test
        void invalidMetadataIsRejected() throws Exception {
            Tenant t = newTenant();
            Instant end = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            byte[] audio = OggOpus.silence(500, 0);

            assertProblem(putSegment(t.ingestKey(), segmentId(), "{not json", audio), 400);
            assertProblem(putSegment(t.ingestKey(), segmentId(), metadata(t.sourceId(), end, end.minusSeconds(10)), audio), 400);
            assertProblem(putSegment(t.ingestKey(), segmentId(), """
                    {"captureStart":"%s","captureEnd":"%s","voicedDuration":"PT6S"}""".formatted(end.minusSeconds(10), end), audio), 400);
            assertProblem(putSegment(t.ingestKey(), segmentId(), """
                    {"sourceId":"%s","captureStart":"%s","captureEnd":"%s","voicedDuration":"6 seconds"}"""
                    .formatted(t.sourceId(), end.minusSeconds(10), end), audio), 400);
            assertProblem(putSegment(t.ingestKey(), segmentId(), """
                    {"sourceId":"%s","captureStart":"%s","captureEnd":"%s","voicedDuration":"PT6S","speaker":"Ana"}"""
                    .formatted(t.sourceId(), end.minusSeconds(10), end), audio), 400);
        }

        @Test
        void voiceCannotLastLongerThanTheSegment() throws Exception {
            Tenant t = newTenant();
            Instant end = Instant.now().truncatedTo(ChronoUnit.MILLIS);

            assertProblem(putSegment(t.ingestKey(), segmentId(), """
                    {"sourceId":"%s","captureStart":"%s","captureEnd":"%s","voicedDuration":"PT11S"}"""
                    .formatted(t.sourceId(), end.minusSeconds(10), end), OggOpus.silence(500, 0)), 400);
        }
    }

    @Nested
    class ClockSkew {

        @Test
        void aCaptureEndFarInThePastIsAcceptedAndFlagged() throws Exception {
            Tenant t = newTenant();
            Instant end = Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);

            HttpResponse<String> response = putSegment(t.ingestKey(), segmentId(),
                    metadata(t.sourceId(), end.minusSeconds(10), end), OggOpus.silence(500, 0));

            assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
            assertThat(json(response, "$.clockSkewSuspected")).isEqualTo("true");
        }

        @Test
        void aCaptureEndInTheFutureIsAcceptedAndFlagged() throws Exception {
            Tenant t = newTenant();
            Instant end = Instant.now().plus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);

            HttpResponse<String> response = putSegment(t.ingestKey(), segmentId(),
                    metadata(t.sourceId(), end.minusSeconds(10), end), OggOpus.silence(500, 0));

            assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
            assertThat(json(response, "$.clockSkewSuspected")).isEqualTo("true");
        }

        @Test
        void aRetryAMinuteLaterIsNotSkew() throws Exception {
            // Retries arrive late by design (ADR-0022): late within the threshold is not a wrong clock.
            Tenant t = newTenant();
            Instant end = Instant.now().minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);

            HttpResponse<String> response = putSegment(t.ingestKey(), segmentId(),
                    metadata(t.sourceId(), end.minusSeconds(10), end), OggOpus.silence(500, 0));

            assertThat(json(response, "$.clockSkewSuspected")).isEqualTo("false");
        }
    }
}
