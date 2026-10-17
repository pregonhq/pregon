package io.github.pregonhq.pregon.server.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@code PREGON_INGEST_MAX_SEGMENT_BYTES} is the only size limit. Spring's own multipart limit is
 * also 1 MB by default, so the default value alone cannot tell which of the two answered the 413.
 */
class SegmentSizeLimitTest extends IngestTestSupport {

    static final int LIMIT = 2_000_000;

    @DynamicPropertySource
    static void limits(DynamicPropertyRegistry registry) {
        registry.add("PREGON_INGEST_MAX_SEGMENT_BYTES", () -> String.valueOf(LIMIT));
    }

    @Test
    void aRaisedLimitAcceptsSegmentsUpToIt() throws Exception {
        Tenant t = newTenant();

        HttpResponse<String> response = putSegment(t.ingestKey(), segmentId(), metadata(t.sourceId()), OggOpus.ofSize(LIMIT));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
    }

    @Test
    void andRejectsOneByteMore() throws Exception {
        Tenant t = newTenant();
        assertUploadsAreServed(t);

        assertProblem(putSegment(t.ingestKey(), segmentId(), metadata(t.sourceId()), OggOpus.ofSize(LIMIT + 1)), 413);
    }
}
