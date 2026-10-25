package io.github.pregonhq.pregon.server.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.Socket;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The in-flight byte budget (ADR-0025 §4) with a tiny value: 20 000 bytes. Two stalled uploads of
 * about 8 400 bytes each fill it; a third one must be refused before its body is read, and the
 * budget must come back when the stalled clients go away, or a storm of broken connections would
 * leak it to zero.
 */
class SegmentInflightBudgetTest extends IngestTestSupport {

    static final int SEGMENT = 8_000;
    static final long CAPACITY = 20_000;

    @Autowired
    InflightBudget budget;

    @DynamicPropertySource
    static void limits(DynamicPropertyRegistry registry) {
        registry.add("PREGON_INGEST_INFLIGHT_MAX_BYTES", () -> "20000");
    }

    @Test
    void anUploadBeyondTheBudgetIsRefusedWhileOthersAreStillArriving() throws Exception {
        Tenant t = newTenant();
        byte[] body = multipart(metadata(t.sourceId()), OggOpus.ofSize(SEGMENT));

        List<Socket> stalled = stalledUploads(t.ingestKey(), body, 2);
        try {
            awaitHeld(2L * body.length);
            HttpResponse<String> third = putSegment(t.ingestKey(), segmentId(), body);

            assertProblem(third, 503);
            assertThat(third.headers().firstValue("Retry-After")).hasValueSatisfying(
                    seconds -> assertThat(Integer.parseInt(seconds)).isPositive());
        } finally {
            close(stalled);
        }
    }

    @Test
    void theBudgetComesBackWhenAStalledClientDisconnects() throws Exception {
        Tenant t = newTenant();
        byte[] body = multipart(metadata(t.sourceId()), OggOpus.ofSize(SEGMENT));

        List<Socket> stalled = stalledUploads(t.ingestKey(), body, 2);
        awaitHeld(2L * body.length);
        assertProblem(putSegment(t.ingestKey(), segmentId(), body), 503);
        close(stalled);

        awaitHeld(0);
        HttpResponse<String> response = putSegment(t.ingestKey(), segmentId(), body);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
    }

    /**
     * Waits until the budget holds exactly {@code bytes}. The server takes a stalled upload's bytes
     * only when it has read its headers and gives them back only when it next reads the closed
     * socket; on a slow machine both happen after this test's next request would, so the test
     * watches the budget itself instead of guessing with a clock.
     */
    private void awaitHeld(long bytes) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (CAPACITY - budget.available() != bytes) {
            if (!Instant.now().isBefore(deadline)) {
                throw new AssertionError("expected " + bytes + " bytes held, but the budget holds " + (CAPACITY - budget.available()));
            }
            Thread.sleep(20);
        }
    }

    @Test
    void aBodyLargerThanTheLimitIsRefusedWithoutReadingIt() throws Exception {
        // Only the headers are sent: a server that waits for the body never answers. The length is
        // over our limit but under Spring's default multipart limit (10 MB), so only our check can
        // produce the 413.
        Tenant t = newTenant();
        assertUploadsAreServed(t);

        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(uploadHeaders(t.ingestKey(), segmentId(), 5_000_000L));
            socket.getOutputStream().flush();

            assertThat(readStatus(socket)).isEqualTo(413);
        }
    }

    @Test
    void theBudgetIsAccountedBeforeAuthorizationIsEvenNeeded() throws Exception {
        // A client without a key must not be able to hold budget: it is refused before taking any.
        Tenant t = newTenant();
        byte[] body = multipart(metadata(t.sourceId()), OggOpus.ofSize(SEGMENT));

        List<Socket> anonymous = stalledUploads(null, body, 3);
        try {
            HttpResponse<String> legitimate = putSegment(t.ingestKey(), segmentId(), body);

            assertThat(legitimate.statusCode()).as(legitimate.body()).isEqualTo(202);
        } finally {
            close(anonymous);
        }
    }

    private List<Socket> stalledUploads(String key, byte[] body, int count) throws Exception {
        List<Socket> sockets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            sockets.add(openPartialUpload(key, segmentId(), body, 1_000));
        }
        return sockets;
    }

    private static void close(List<Socket> sockets) throws IOException {
        for (Socket socket : sockets) {
            socket.close();
        }
    }
}
