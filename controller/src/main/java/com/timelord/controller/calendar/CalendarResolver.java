package com.timelord.controller.calendar;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Turns a calendar's weekly rules and dated overrides into concrete off
 * windows. Pure and stateless so the precedence rules are easy to test.
 *
 * <p>Every window is keyed by the date its night <em>starts</em>: for that
 * date an override covering it wins over the weekday rule, and among
 * overlapping overrides the narrowest range wins (latest-created on a tie).
 */
public final class CalendarResolver {

    private CalendarResolver() {
    }

    /** Off windows overlapping [from, to), earliest first, with touching or overlapping windows merged. */
    public static List<OffWindow> windows(ZoneId zone, List<CalendarRule> rules, List<CalendarOverride> overrides,
                                          Instant from, Instant to) {
        LocalDate first = from.atZone(zone).toLocalDate().minusDays(1);
        LocalDate last = to.atZone(zone).toLocalDate();

        List<OffWindow> windows = new ArrayList<>();
        for (LocalDate night = first; !night.isAfter(last); night = night.plusDays(1)) {
            Optional<Times> times = timesFor(night, rules, overrides);
            if (times.isEmpty()) {
                continue;
            }
            Times t = times.get();
            // 22:30 -> 06:30 starts that evening; 01:00 -> 06:30 starts after midnight.
            LocalDate offDate = t.off().isBefore(t.on()) ? night.plusDays(1) : night;
            Instant offAt = offDate.atTime(t.off()).atZone(zone).toInstant();
            Instant onAt = night.plusDays(1).atTime(t.on()).atZone(zone).toInstant();
            if (onAt.isAfter(from) && offAt.isBefore(to)) {
                windows.add(new OffWindow(offAt, onAt));
            }
        }
        return merge(windows);
    }

    private static Optional<Times> timesFor(LocalDate night, List<CalendarRule> rules, List<CalendarOverride> overrides) {
        Optional<CalendarOverride> override = overrides.stream()
                .filter(o -> !night.isBefore(o.getStartDate()) && !night.isAfter(o.getEndDate()))
                .min(Comparator
                        .comparingLong((CalendarOverride o) -> ChronoUnit.DAYS.between(o.getStartDate(), o.getEndDate()))
                        .thenComparing(CalendarOverride::getCreatedAt, Comparator.reverseOrder()));
        if (override.isPresent()) {
            return override.map(o -> new Times(o.getOffTime(), o.getOnTime()));
        }
        return rules.stream()
                .filter(r -> r.getDays().contains(night.getDayOfWeek()))
                .findFirst()
                .map(r -> new Times(r.getOffTime(), r.getOnTime()));
    }

    private static List<OffWindow> merge(List<OffWindow> sorted) {
        List<OffWindow> sortedCopy = new ArrayList<>(sorted);
        sortedCopy.sort(Comparator.comparing(OffWindow::offAt));
        List<OffWindow> merged = new ArrayList<>();
        for (OffWindow w : sortedCopy) {
            if (!merged.isEmpty() && !w.offAt().isAfter(merged.get(merged.size() - 1).onAt())) {
                OffWindow prev = merged.remove(merged.size() - 1);
                Instant on = w.onAt().isAfter(prev.onAt()) ? w.onAt() : prev.onAt();
                merged.add(new OffWindow(prev.offAt(), on));
            } else {
                merged.add(w);
            }
        }
        return merged;
    }

    private record Times(LocalTime off, LocalTime on) {
    }
}
