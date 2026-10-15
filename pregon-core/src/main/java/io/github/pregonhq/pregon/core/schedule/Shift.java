package io.github.pregonhq.pregon.core.schedule;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A named set of weekly windows of a location, such as "Morning" (ADR-0022). */
public record Shift(String name, List<TimeWindow> windows) {

    public Shift {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("a shift needs a name");
        }
        if (windows == null || windows.isEmpty()) {
            throw new IllegalArgumentException("a shift needs at least one window");
        }
        windows = List.copyOf(windows);
    }

    /**
     * The first pair of shifts that overlap, if any. An interaction belongs to the shift in which
     * it starts (ADR-0022), so shifts of a location must not overlap. Names must also be unique.
     */
    public static Optional<Conflict> firstConflict(List<Shift> shifts) {
        List<Shift> seen = new ArrayList<>();
        for (int i = 0; i < shifts.size(); i++) {
            Shift shift = shifts.get(i);
            for (int j = 0; j < seen.size(); j++) {
                Shift earlier = seen.get(j);
                if (earlier.name().equalsIgnoreCase(shift.name())) {
                    return Optional.of(new Conflict(j, i, "two shifts are called " + shift.name()));
                }
                for (TimeWindow window : shift.windows()) {
                    for (TimeWindow earlierWindow : earlier.windows()) {
                        if (window.overlaps(earlierWindow)) {
                            return Optional.of(new Conflict(j, i,
                                    "shifts " + earlier.name() + " and " + shift.name() + " overlap"));
                        }
                    }
                }
            }
            seen.add(shift);
        }
        return Optional.empty();
    }

    /** Indexes of two conflicting shifts in the list, and why they conflict. */
    public record Conflict(int first, int second, String reason) {
    }
}
