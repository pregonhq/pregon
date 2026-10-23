package io.github.pregonhq.pregon.server.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.Period;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import io.github.pregonhq.pregon.server.InfrastructureContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Monthly partitions of {@code segment} (ADR-0025 §8): bounds, names, creation ahead and retention. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SegmentPartitionsTest {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        InfrastructureContainers.register(registry);
    }

    @Autowired
    JdbcClient jdbc;

    @Test
    void theLowerBoundOfAMonthIsItsFirstMillisecondAsAUuidv7() {
        YearMonth october = YearMonth.of(2026, 10);
        UUID bound = SegmentPartitions.lowerBound(october);

        assertThat(bound.getMostSignificantBits() >>> 16).isEqualTo(Instant.parse("2026-10-01T00:00:00Z").toEpochMilli());
        assertThat(bound.getLeastSignificantBits()).isZero();
        assertThat(SegmentPartitions.monthOf(bound)).isEqualTo(october);
        assertThat(SegmentPartitions.name(october)).isEqualTo("segment_2026_10");
        // The id of a segment captured in that month sorts inside its bounds.
        UUID captured = IngestTestSupport.segmentIdAt(Instant.parse("2026-10-19T15:00:00Z"));
        assertThat(captured.compareTo(bound)).isPositive();
        assertThat(captured.compareTo(SegmentPartitions.lowerBound(october.plusMonths(1)))).isNegative();
    }

    @Test
    void theMigrationAndTheMaintenanceAgreeOnTheCurrentAndNextMonths() {
        YearMonth current = YearMonth.from(Instant.now().atOffset(ZoneOffset.UTC));

        List<String> partitions = partitions();

        assertThat(partitions).contains("segment_default");
        for (int ahead = 0; ahead <= SegmentPartitions.MONTHS_AHEAD; ahead++) {
            assertThat(partitions).contains(SegmentPartitions.name(current.plusMonths(ahead)));
        }
    }

    @Test
    void aMonthOlderThanTheRetentionIsDroppedAndTheFutureIsCreated() {
        YearMonth current = YearMonth.from(Instant.now().atOffset(ZoneOffset.UTC));
        YearMonth old = current.minusMonths(3);
        jdbc.sql("CREATE TABLE IF NOT EXISTS " + SegmentPartitions.name(old) + " PARTITION OF segment FOR VALUES FROM ('"
                + SegmentPartitions.lowerBound(old) + "') TO ('" + SegmentPartitions.lowerBound(old.plusMonths(1)) + "')").update();
        MutableClock clock = new MutableClock(Instant.now());

        new SegmentPartitions(jdbc, clock, Period.ofMonths(1)).maintain();

        assertThat(partitions()).doesNotContain(SegmentPartitions.name(old))
                .contains(SegmentPartitions.name(current.plusMonths(SegmentPartitions.MONTHS_AHEAD)), "segment_default");
    }

    private List<String> partitions() {
        return jdbc.sql("""
                SELECT child.relname FROM pg_inherits
                JOIN pg_class parent ON pg_inherits.inhparent = parent.oid
                JOIN pg_class child ON pg_inherits.inhrelid = child.oid
                WHERE parent.relname = 'segment'""").query(String.class).list();
    }
}
