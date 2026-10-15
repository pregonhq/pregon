package io.github.pregonhq.pregon.core.schedule;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A weekly window in local time, such as "Monday to Friday, 06:00-14:00". When {@code end} is
 * earlier than {@code start} the window crosses midnight and ends the next day (a bar open
 * 18:00-02:00). A window of zero length is not allowed.
 */
public record TimeWindow(Set<DayOfWeek> days, LocalTime start, LocalTime end) {

    private static final int MINUTES_PER_DAY = 24 * 60;
    private static final int MINUTES_PER_WEEK = 7 * MINUTES_PER_DAY;

    public TimeWindow {
        Objects.requireNonNull(days, "days");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (days.isEmpty()) {
            throw new IllegalArgumentException("a window needs at least one day");
        }
        if (start.equals(end)) {
            throw new IllegalArgumentException("a window cannot start and end at the same time");
        }
        if (start.getSecond() != 0 || start.getNano() != 0 || end.getSecond() != 0 || end.getNano() != 0) {
            throw new IllegalArgumentException("windows have minute precision");
        }
        days = Set.copyOf(EnumSet.copyOf(days));
    }

    public boolean crossesMidnight() {
        return end.isBefore(start);
    }

    /** True when both windows share at least one minute of the week, including across Sunday night. */
    public boolean overlaps(TimeWindow other) {
        for (int[] mine : intervals()) {
            for (int[] theirs : other.intervals()) {
                if (mine[0] < theirs[1] && theirs[0] < mine[1]) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Half-open minute-of-week intervals, split where a window wraps from Sunday to Monday. */
    private List<int[]> intervals() {
        List<int[]> intervals = new ArrayList<>();
        int startMinute = start.getHour() * 60 + start.getMinute();
        int length = Math.floorMod(end.getHour() * 60 + end.getMinute() - startMinute, MINUTES_PER_DAY);
        for (DayOfWeek day : days) {
            int from = (day.getValue() - 1) * MINUTES_PER_DAY + startMinute;
            int to = from + length;
            if (to <= MINUTES_PER_WEEK) {
                intervals.add(new int[] {from, to});
            } else {
                intervals.add(new int[] {from, MINUTES_PER_WEEK});
                intervals.add(new int[] {0, to - MINUTES_PER_WEEK});
            }
        }
        return intervals;
    }
}
