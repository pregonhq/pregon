package io.github.pregonhq.pregon.server.ingest;

import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Monthly partitions of {@code segment} (ADR-0025 §8): created before they are needed, dropped when
 * older than the retention period. A UUIDv7 carries Unix milliseconds in its high 48 bits, so the
 * bound of a month is the id whose timestamp is the first millisecond of that month and whose other
 * bits are zero.
 *
 * <p>Runs at startup and once a day. {@code V3} creates the first partitions with the same names
 * and bounds, so both sides agree and {@code IF NOT EXISTS} makes every run idempotent.
 */
public final class SegmentPartitions {

    static final int MONTHS_AHEAD = 2;
    private static final Logger log = LoggerFactory.getLogger(SegmentPartitions.class);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Period retention;

    public SegmentPartitions(JdbcClient jdbc, Clock clock, Period retention) {
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("retention must be a positive period, got " + retention);
        }
        this.jdbc = jdbc;
        this.clock = clock;
        this.retention = retention;
    }

    /** Creates the current month and the next {@link #MONTHS_AHEAD}; drops months entirely older than the retention. */
    public void maintain() {
        YearMonth current = YearMonth.from(clock.instant().atOffset(ZoneOffset.UTC));
        for (int ahead = 0; ahead <= MONTHS_AHEAD; ahead++) {
            YearMonth month = current.plusMonths(ahead);
            jdbc.sql("CREATE TABLE IF NOT EXISTS " + name(month) + " PARTITION OF segment FOR VALUES FROM ('"
                    + lowerBound(month) + "') TO ('" + lowerBound(month.plusMonths(1)) + "')").update();
        }
        YearMonth oldest = YearMonth.from(clock.instant().atOffset(ZoneOffset.UTC).minus(retention));
        for (String partition : existing()) {
            YearMonth month = monthOf(partition);
            if (month != null && month.isBefore(oldest)) {
                // DROP, not DELETE: instant, and nothing to vacuum (ADR-0025 §8).
                jdbc.sql("DROP TABLE IF EXISTS " + partition).update();
                log.info("dropped segment partition {} (older than {})", partition, retention);
            }
        }
        long inDefault = jdbc.sql("SELECT count(*) FROM segment_default").query(Long.class).single();
        if (inDefault > 0) {
            log.warn("{} segments in the DEFAULT partition: a month without its partition, or agents with a wrong clock", inDefault);
        }
    }

    static String name(YearMonth month) {
        return String.format("segment_%04d_%02d", month.getYear(), month.getMonthValue());
    }

    /** The smallest UUIDv7 of the month: its first millisecond, every other bit zero. */
    static UUID lowerBound(YearMonth month) {
        long millis = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        return new UUID(millis << 16, 0L);
    }

    /** The month a UUIDv7 was generated in, by its timestamp. */
    static YearMonth monthOf(UUID id) {
        return YearMonth.from(Instant.ofEpochMilli(id.getMostSignificantBits() >>> 16).atOffset(ZoneOffset.UTC));
    }

    private List<String> existing() {
        return jdbc.sql("""
                SELECT child.relname FROM pg_inherits
                JOIN pg_class parent ON pg_inherits.inhparent = parent.oid
                JOIN pg_class child ON pg_inherits.inhrelid = child.oid
                WHERE parent.relname = 'segment'""").query(String.class).list();
    }

    private static YearMonth monthOf(String partition) {
        if (!partition.matches("segment_\\d{4}_\\d{2}")) {
            return null; // segment_default
        }
        return YearMonth.of(Integer.parseInt(partition.substring(8, 12)), Integer.parseInt(partition.substring(13, 15)));
    }
}
