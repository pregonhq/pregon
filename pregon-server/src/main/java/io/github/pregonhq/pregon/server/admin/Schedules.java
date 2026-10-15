package io.github.pregonhq.pregon.server.admin;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.pregonhq.pregon.core.schedule.Shift;
import io.github.pregonhq.pregon.core.schedule.TimeWindow;

/** JSON shapes of time windows and shifts, and their validation into the core types. */
final class Schedules {

    private Schedules() {
    }

    record WindowJson(List<String> days, String start, String end) {
    }

    record ShiftJson(String name, List<WindowJson> windows) {
    }

    static List<TimeWindow> windows(Validation v, String pointer, List<WindowJson> windows, boolean required) {
        List<TimeWindow> result = new ArrayList<>();
        if (windows == null || windows.isEmpty()) {
            if (required) {
                v.add(pointer, "needs at least one window");
            }
            return result;
        }
        for (int i = 0; i < windows.size(); i++) {
            TimeWindow window = window(v, pointer + "/" + i, windows.get(i));
            if (window != null) {
                result.add(window);
            }
        }
        return result;
    }

    static List<Shift> shifts(Validation v, String pointer, List<ShiftJson> shifts) {
        if (shifts == null) {
            v.add(pointer, "is required (an empty list is allowed)");
            return List.of();
        }
        List<Shift> result = new ArrayList<>();
        for (int i = 0; i < shifts.size(); i++) {
            ShiftJson json = shifts.get(i);
            String at = pointer + "/" + i;
            if (json == null) {
                v.add(at, "is required");
                continue;
            }
            String name = v.text(at + "/name", json.name(), 60);
            List<TimeWindow> windows = windows(v, at + "/windows", json.windows(), true);
            if (name != null && !windows.isEmpty() && windows.size() == json.windows().size()) {
                result.add(new Shift(name, windows));
            }
        }
        if (v.ok()) {
            Shift.firstConflict(result).ifPresent(conflict -> v.add(pointer + "/" + conflict.second(), conflict.reason()));
        }
        return result;
    }

    static List<WindowJson> toJson(List<TimeWindow> windows) {
        return windows.stream().map(Schedules::toJson).toList();
    }

    static WindowJson toJson(TimeWindow window) {
        return new WindowJson(window.days().stream().sorted().map(DayOfWeek::name).toList(),
                window.start().toString(), window.end().toString());
    }

    static List<ShiftJson> shiftsToJson(List<Shift> shifts) {
        return shifts.stream().map(shift -> new ShiftJson(shift.name(), toJson(shift.windows()))).toList();
    }

    private static TimeWindow window(Validation v, String at, WindowJson json) {
        if (json == null) {
            v.add(at, "is required");
            return null;
        }
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        if (json.days() == null || json.days().isEmpty()) {
            v.add(at + "/days", "needs at least one day");
        } else {
            Set<String> seen = new HashSet<>();
            for (int d = 0; d < json.days().size(); d++) {
                String day = json.days().get(d);
                try {
                    days.add(DayOfWeek.valueOf(day));
                    if (!seen.add(day)) {
                        v.add(at + "/days/" + d, "is repeated");
                    }
                } catch (IllegalArgumentException | NullPointerException e) {
                    v.add(at + "/days/" + d, "must be MONDAY to SUNDAY");
                }
            }
        }
        LocalTime start = v.localTime(at + "/start", json.start());
        LocalTime end = v.localTime(at + "/end", json.end());
        if (start != null && start.equals(end)) {
            v.add(at + "/end", "must differ from start");
            return null;
        }
        if (days.isEmpty() || start == null || end == null) {
            return null;
        }
        return new TimeWindow(days, start, end);
    }
}
