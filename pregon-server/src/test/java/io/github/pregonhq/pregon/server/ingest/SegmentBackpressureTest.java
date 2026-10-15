package io.github.pregonhq.pregon.server.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Backpressure (ADR-0024 §7) with a tiny queue: 40 000 bytes per tenant, half of it per source.
 *
 * <p>Assumes nothing drains the queue while a test runs: there is no transcription sidecar here,
 * so queued audio stays queued.
 */
class SegmentBackpressureTest extends IngestTestSupport {

    static final int SEGMENT = 8_000;

    @DynamicPropertySource
    static void limits(DynamicPropertyRegistry registry) {
        registry.add("PREGON_INGEST_QUEUE_MAX_BYTES", () -> "40000");
        registry.add("PREGON_INGEST_QUEUE_SOURCE_SHARE", () -> "0.5");
    }

    @Test
    void aSourceAboveItsShareIsToldToComeBackLater() throws Exception {
        Tenant t = newTenant();

        accept(t, t.sourceId());
        accept(t, t.sourceId());
        HttpResponse<String> third = upload(t, t.sourceId());

        assertProblem(third, 503);
        assertRetryAfter(third);
    }

    @Test
    void oneNoisySourceDoesNotStarveTheOthers() throws Exception {
        Tenant t = newTenant();
        String quiet = newSource(t.adminKey());

        accept(t, t.sourceId());
        accept(t, t.sourceId());
        assertProblem(upload(t, t.sourceId()), 503);

        accept(t, quiet);
    }

    @Test
    void aFullTenantQueueStopsEverySource() throws Exception {
        Tenant t = newTenant();
        String second = newSource(t.adminKey());
        String third = newSource(t.adminKey());

        accept(t, t.sourceId());
        accept(t, t.sourceId());
        accept(t, second);
        accept(t, second);
        accept(t, third);
        HttpResponse<String> overTheTenant = upload(t, third);

        assertProblem(overTheTenant, 503);
        assertRetryAfter(overTheTenant);
    }

    @Test
    void oneTenantsBacklogDoesNotStopAnother() throws Exception {
        Tenant busy = newTenant();
        Tenant other = newTenant();

        accept(busy, busy.sourceId());
        accept(busy, busy.sourceId());
        assertProblem(upload(busy, busy.sourceId()), 503);

        accept(other, other.sourceId());
    }

    @Test
    void aRetryOfAnAcceptedSegmentGetsItsReceiptEvenWhenTheQueueIsFull() throws Exception {
        // A 503 here would keep the agent retrying a segment the server already has.
        Tenant t = newTenant();
        String id = segmentId();
        byte[] body = multipart(metadata(t.sourceId()), OggOpus.ofSize(SEGMENT));

        assertThat(putSegment(t.ingestKey(), id, body).statusCode()).isEqualTo(202);
        accept(t, t.sourceId());
        assertProblem(upload(t, t.sourceId()), 503);

        assertThat(putSegment(t.ingestKey(), id, body).statusCode()).isEqualTo(200);
    }

    private void accept(Tenant t, String sourceId) throws Exception {
        HttpResponse<String> response = upload(t, sourceId);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
    }

    private HttpResponse<String> upload(Tenant t, String sourceId) throws Exception {
        return putSegment(t.ingestKey(), segmentId(), metadata(sourceId), OggOpus.ofSize(SEGMENT));
    }

    private static void assertRetryAfter(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Retry-After")).hasValueSatisfying(
                seconds -> assertThat(Integer.parseInt(seconds)).isPositive());
    }
}
