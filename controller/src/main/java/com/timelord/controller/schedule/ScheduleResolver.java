package com.timelord.controller.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Turns a schedule's rows and overrides into concrete times. Pure and stateless so the rules are easy
 * to test.
 *
 * <p>A schedule is a set of <em>allowed</em> ("on") windows; everything outside them is off. Each day is
 * its own midnight-to-midnight line: a row contributes a window on every day it ticks, from its start time
 * to its end time within that day, where an end of 00:00 means midnight at the end of the day (so
 * 00:00 to 00:00 is the whole day). Windows never wrap: a window that ends at midnight simply meets the
 * next day's window if that starts at 00:00, at which point the next day takes over. Any number of rows
 * may cover the same day. Overrides are layered on afterwards: each adds the same kind of window for
 * every date in its range. All windows are unioned, so an override can extend a day but never narrow it.
 *
 * <p>A schedule with no rows restricts nothing, so a freshly created, still-empty schedule does not
 * keep a device asleep.
 */
public final class ScheduleResolver {

    private ScheduleResolver() {
    }

    /** The allowed windows that overlap [from, to), earliest first, with touching or overlapping ones merged. */
    public static List<AllowedWindow> allowedWindows(ZoneId zone, List<ScheduleRule> rules,
                                                     List<ScheduleOverride> overrides, Instant from, Instant to) {
        rules = rules.stream().filter(ScheduleRule::isEnabled).toList();
        overrides = overrides.stream().filter(ScheduleOverride::isEnabled).toList();
        List<AllowedWindow> windows = new ArrayList<>();
        // Windows stay inside their day, so one day back is enough to catch one still running at `from`.
        LocalDate first = from.atZone(zone).toLocalDate().minusDays(1);
        LocalDate last = to.atZone(zone).toLocalDate();
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            for (ScheduleRule rule : rules) {
                if (rule.getDays().contains(day.getDayOfWeek())) {
                    window(zone, day, rule.getStartTime(), rule.getEndTime()).ifPresent(windows::add);
                }
            }
            for (ScheduleOverride override : overrides) {
                if (!day.isBefore(override.getStartDate()) && !day.isAfter(override.getEndDate())) {
                    window(zone, day, override.getStartTime(), override.getEndTime()).ifPresent(windows::add);
                }
            }
        }
        return merge(windows).stream().filter(w -> w.offAt().isAfter(from) && w.onAt().isBefore(to)).toList();
    }

    /**
     * The spans overlapping [from, to) in which the device must be off: the gaps between the allowed
     * windows. Empty for a schedule with no rows. The first gap begins no earlier than local midnight of
     * the day before {@code from} and the last ends at local midnight after {@code to}, so the answer
     * only changes when something real does (not on every call).
     */
    public static List<OffWindow> offWindows(ZoneId zone, List<ScheduleRule> rules, List<ScheduleOverride> overrides,
                                             Instant from, Instant to) {
        // Disabled rows count for nothing, so a schedule whose rows are all disabled restricts nothing.
        if (rules.stream().noneMatch(ScheduleRule::isEnabled)) {
            return List.of();
        }
        Instant rangeStart = from.atZone(zone).toLocalDate().minusDays(1).atStartOfDay(zone).toInstant();
        Instant rangeEnd = to.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant();
        List<AllowedWindow> allowed = allowedWindows(zone, rules, overrides, rangeStart, rangeEnd);

        List<OffWindow> gaps = new ArrayList<>();
        Instant cursor = rangeStart;
        for (AllowedWindow w : allowed) {
            if (w.onAt().isAfter(cursor)) {
                gaps.add(new OffWindow(cursor, w.onAt().isBefore(rangeEnd) ? w.onAt() : rangeEnd));
            }
            if (w.offAt().isAfter(cursor)) {
                cursor = w.offAt();
            }
        }
        if (cursor.isBefore(rangeEnd)) {
            gaps.add(new OffWindow(cursor, rangeEnd));
        }
        return gaps.stream().filter(g -> g.onAt().isAfter(from) && g.offAt().isBefore(to)).toList();
    }

    /** A blank start means the start of the day. */
    public static LocalTime startOrMidnight(LocalTime start) {
        return start != null ? start : LocalTime.MIDNIGHT;
    }

    /** A blank end means midnight at the end of the day (00:00 is how that is stored). */
    public static LocalTime endOrMidnight(LocalTime end) {
        return end != null ? end : LocalTime.MIDNIGHT;
    }

    /** A window must end after it starts within its day; an end of 00:00 means midnight at the end of the day. */
    public static boolean isValidWindow(LocalTime start, LocalTime end) {
        return end.equals(LocalTime.MIDNIGHT) || end.isAfter(start);
    }

    static Optional<AllowedWindow> window(ZoneId zone, LocalDate day, LocalTime start, LocalTime end) {
        if (!isValidWindow(start, end)) {
            return Optional.empty();
        }
        LocalDateTime on = day.atTime(start);
        LocalDateTime off = end.equals(LocalTime.MIDNIGHT) ? day.plusDays(1).atStartOfDay() : day.atTime(end);
        return Optional.of(new AllowedWindow(on.atZone(zone).toInstant(), off.atZone(zone).toInstant()));
    }

    private static List<AllowedWindow> merge(List<AllowedWindow> windows) {
        List<AllowedWindow> sorted = new ArrayList<>(windows);
        sorted.sort(Comparator.comparing(AllowedWindow::onAt));
        List<AllowedWindow> merged = new ArrayList<>();
        for (AllowedWindow w : sorted) {
            if (!merged.isEmpty() && !w.onAt().isAfter(merged.get(merged.size() - 1).offAt())) {
                AllowedWindow prev = merged.remove(merged.size() - 1);
                merged.add(new AllowedWindow(prev.onAt(), w.offAt().isAfter(prev.offAt()) ? w.offAt() : prev.offAt()));
            } else {
                merged.add(w);
            }
        }
        return merged;
    }
}
