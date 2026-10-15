package io.github.pregonhq.pregon.core.schedule;

import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.SATURDAY;
import static java.time.DayOfWeek.SUNDAY;
import static java.time.DayOfWeek.TUESDAY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TimeWindowTest {

    private static TimeWindow window(Set<DayOfWeek> days, String start, String end) {
        return new TimeWindow(days, LocalTime.parse(start), LocalTime.parse(end));
    }

    @Test
    void consecutiveWindowsTouchWithoutOverlapping() {
        assertFalse(window(Set.of(MONDAY), "06:00", "14:00").overlaps(window(Set.of(MONDAY), "14:00", "22:00")));
    }

    @Test
    void windowsOnTheSameDayThatShareAMinuteOverlap() {
        assertTrue(window(Set.of(MONDAY), "06:00", "14:01").overlaps(window(Set.of(MONDAY), "14:00", "22:00")));
    }

    @Test
    void sameHoursOnDifferentDaysDoNotOverlap() {
        assertFalse(window(Set.of(MONDAY), "06:00", "14:00").overlaps(window(Set.of(TUESDAY), "06:00", "14:00")));
    }

    @Test
    void aWindowCrossingMidnightReachesIntoTheNextDay() {
        TimeWindow bar = window(Set.of(FRIDAY), "18:00", "02:00");

        assertTrue(bar.crossesMidnight());
        assertTrue(bar.overlaps(window(Set.of(SATURDAY), "01:00", "03:00")));
        assertFalse(bar.overlaps(window(Set.of(SATURDAY), "02:00", "10:00")));
    }

    @Test
    void sundayNightWrapsToMondayMorning() {
        TimeWindow sundayNight = window(Set.of(SUNDAY), "22:00", "06:00");

        assertTrue(sundayNight.overlaps(window(Set.of(MONDAY), "05:00", "07:00")));
        assertFalse(sundayNight.overlaps(window(Set.of(MONDAY), "06:00", "07:00")));
    }

    @Test
    void rejectsInvalidWindows() {
        assertThrows(IllegalArgumentException.class, () -> window(Set.of(), "06:00", "14:00"));
        assertThrows(IllegalArgumentException.class, () -> window(Set.of(MONDAY), "06:00", "06:00"));
        assertThrows(IllegalArgumentException.class,
                () -> new TimeWindow(Set.of(MONDAY), LocalTime.of(6, 0, 30), LocalTime.of(14, 0)));
    }

    @Test
    void findsOverlappingOrDuplicateShifts() {
        Shift morning = new Shift("Morning", List.of(window(Set.of(MONDAY, TUESDAY), "06:00", "14:00")));
        Shift evening = new Shift("Evening", List.of(window(Set.of(MONDAY, TUESDAY), "14:00", "22:00")));
        Shift night = new Shift("Night", List.of(window(Set.of(MONDAY), "21:00", "06:00")));

        assertTrue(Shift.firstConflict(List.of(morning, evening)).isEmpty());
        var overlap = Shift.firstConflict(List.of(morning, evening, night)).orElseThrow();
        assertEquals(1, overlap.first());
        assertEquals(2, overlap.second());
        assertTrue(Shift.firstConflict(List.of(morning, new Shift("morning", evening.windows()))).isPresent());
    }
}
