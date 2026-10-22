package com.timelord.controller.calendar;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts the earlier "off window" model (V3/V4: days + off time + on time, one rule per day, an
 * off time earlier than the on time meaning after midnight) into the current allowed-window rows.
 * Used by the V5 migration; pure so it can be tested without a database. Output rows never run past
 * midnight: an allowed span that crosses it is split across the two days.
 *
 * <p>The weekly rules are converted exactly: the old rules define when the device was off during a
 * repeating week, and the new rows are the gaps between those off spans.
 */
public final class LegacyCalendarConverter {

    private static final int DAY = 24 * 60;
    private static final int WEEK = 7 * DAY;

    private LegacyCalendarConverter() {
    }

    /** One old rule: off at {@code off} on each of {@code days}, on again at the first {@code on} after that. */
    public record LegacyRule(Set<DayOfWeek> days, LocalTime off, LocalTime on) {
    }

    /** One new row: allowed from {@code start} to {@code end} within the day (an end of 00:00 is midnight). */
    public record Row(Set<DayOfWeek> days, LocalTime start, LocalTime end) {
    }

    /** One added window for an override, {@code dayOffset} days after the override's shifted start date. */
    public record OverrideWindow(int dayOffset, LocalTime start, LocalTime end) {
    }

    /**
     * The allowed windows an old override implied, split at midnight so each stays within one day: the
     * device came back on at {@code on} (on date {@code shift}, see {@link #onDayShift}) and stayed on
     * until the next {@code off}. If that off is later in the day it is one window; if it falls the next
     * morning it is two (on..midnight, then midnight..off).
     */
    public static List<OverrideWindow> overrideWindows(LocalTime off, LocalTime on) {
        if (off.isAfter(on)) {
            return List.of(new OverrideWindow(0, on, off));
        }
        List<OverrideWindow> windows = new ArrayList<>();
        windows.add(new OverrideWindow(0, on, LocalTime.MIDNIGHT));
        if (!off.equals(LocalTime.MIDNIGHT)) {
            windows.add(new OverrideWindow(1, LocalTime.MIDNIGHT, off));
        }
        return windows;
    }

    /**
     * What an old rule with an off time earlier than its on time meant, refined as the first fix did:
     * after midnight only if that off time is in the morning (01:00 -> 06:30); an off time from midday
     * (16:15 -> 16:30) was always a same-day window the old model scheduled a day late.
     */
    public static boolean offNextDay(LocalTime off, LocalTime on) {
        return off.isBefore(on) && off.isBefore(LocalTime.NOON);
    }

    /** Days between an old rule's anchor day and the day the device is allowed on again. */
    public static int onDayShift(LocalTime off, LocalTime on) {
        return (offNextDay(off, on) ? 1 : 0) + (on.isAfter(off) ? 0 : 1);
    }

    /**
     * Returns the allowed-window rows equivalent to {@code rules}. An empty result means the old rules
     * left no allowed time at all (or there were none); callers must not treat that as "restricts
     * nothing" without checking which of the two it was.
     */
    public static List<Row> toAllowedRows(List<LegacyRule> rules) {
        boolean[] off = new boolean[WEEK];
        for (LegacyRule rule : rules) {
            int offMin = minutes(rule.off());
            int onMin = minutes(rule.on());
            int nextDay = offNextDay(rule.off(), rule.on()) ? DAY : 0;
            int sameDayOn = rule.on().isAfter(rule.off()) ? 0 : DAY;
            for (DayOfWeek day : rule.days()) {
                int anchor = (day.getValue() - 1) * DAY;
                int start = anchor + nextDay + offMin;
                int end = anchor + nextDay + sameDayOn + onMin;
                for (int t = start; t < end; t++) {
                    off[t % WEEK] = true;
                }
            }
        }

        // Runs of allowed minutes around the (circular) week.
        int startIndex = -1;
        for (int i = 0; i < WEEK; i++) {
            if (!off[i] && off[(i + WEEK - 1) % WEEK]) {
                startIndex = i;
                break;
            }
        }
        if (startIndex < 0) {
            // All off (no allowed time), or all allowed (no rules): nothing to express as rows.
            return rules.isEmpty() ? List.of() : allDayIfNeverOff(off);
        }

        Map<List<LocalTime>, Set<DayOfWeek>> grouped = new LinkedHashMap<>();
        int i = 0;
        while (i < WEEK) {
            int pos = (startIndex + i) % WEEK;
            if (off[pos]) {
                i++;
                continue;
            }
            int length = 0;
            while (i + length < WEEK && !off[(startIndex + i + length) % WEEK]) {
                length++;
            }
            addRuns(grouped, pos, length);
            i += length;
        }
        List<Row> rows = new ArrayList<>();
        grouped.forEach((times, days) -> rows.add(new Row(days, times.get(0), times.get(1))));
        return rows;
    }

    private static List<Row> allDayIfNeverOff(boolean[] off) {
        for (boolean b : off) {
            if (b) {
                return List.of();
            }
        }
        return List.of(new Row(EnumSet.allOf(DayOfWeek.class), LocalTime.MIDNIGHT, LocalTime.MIDNIGHT));
    }

    /** Splits an allowed run starting at week-minute {@code start} into per-day anchored rows. */
    private static void addRuns(Map<List<LocalTime>, Set<DayOfWeek>> grouped, int start, int length) {
        int pos = start;
        int remaining = length;
        while (remaining > 0) {
            int day = (pos / DAY) % 7;
            int startMin = pos % DAY;
            int untilMidnight = DAY - startMin;
            if (remaining <= untilMidnight) {
                put(grouped, day, startMin, (startMin + remaining) % DAY);
                return;
            }
            put(grouped, day, startMin, 0);
            pos += untilMidnight;
            remaining -= untilMidnight;
        }
    }

    private static void put(Map<List<LocalTime>, Set<DayOfWeek>> grouped, int dayIndex, int startMin, int endMin) {
        grouped.computeIfAbsent(List.of(toTime(startMin), toTime(endMin)), k -> EnumSet.noneOf(DayOfWeek.class))
                .add(DayOfWeek.of(dayIndex + 1));
    }

    private static int minutes(LocalTime t) {
        return t.getHour() * 60 + t.getMinute();
    }

    private static LocalTime toTime(int minutes) {
        return LocalTime.of(minutes / 60, minutes % 60);
    }
}
