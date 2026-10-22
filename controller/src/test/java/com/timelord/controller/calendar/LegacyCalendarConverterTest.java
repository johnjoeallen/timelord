package com.timelord.controller.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.timelord.controller.calendar.LegacyCalendarConverter.LegacyRule;
import com.timelord.controller.calendar.LegacyCalendarConverter.Row;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LegacyCalendarConverterTest {

    private static Set<DayOfWeek> days(DayOfWeek... d) {
        return EnumSet.copyOf(List.of(d));
    }

    private static LegacyRule rule(String off, String on, DayOfWeek... d) {
        return new LegacyRule(days(d), LocalTime.parse(off), LocalTime.parse(on));
    }

    private static Row row(String start, String end, DayOfWeek... d) {
        return new Row(days(d), LocalTime.parse(start), LocalTime.parse(end));
    }

    @Test
    void theSchoolNightsCalendarBecomesTheEquivalentAllowedWindows() {
        // Sun-Thu off 22:30 on 06:30; Fri/Sat off 01:00 (after midnight) on 06:30.
        List<Row> rows = LegacyCalendarConverter.toAllowedRows(List.of(
                rule("22:30", "06:30", DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
                rule("01:00", "06:30", DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)));

        // Allowed Mon-Thu and Sun 06:30-22:30; Fri/Sat from 06:30 to midnight, continuing 00:00-01:00 on Sat/Sun.
        assertThat(rows).containsExactlyInAnyOrder(
                row("06:30", "22:30", DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.SUNDAY),
                row("06:30", "00:00", DayOfWeek.FRIDAY, DayOfWeek.SATURDAY),
                row("00:00", "01:00", DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));
    }

    @Test
    void aSameDayOffWindowBecomesAWindowAroundIt() {
        // Thursday only: off 16:15, on 16:30. Everything else is allowed.
        List<Row> rows = LegacyCalendarConverter.toAllowedRows(List.of(rule("16:15", "16:30", DayOfWeek.THURSDAY)));

        // Allowed from Thu 16:30 round the week to Thu 16:15: Thu 16:30 -> midnight, six whole days, Thu 00:00 -> 16:15.
        assertThat(rows).contains(row("16:30", "00:00", DayOfWeek.THURSDAY), row("00:00", "16:15", DayOfWeek.THURSDAY));
        assertThat(rows).contains(row("00:00", "00:00", DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY, DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY));
    }

    @Test
    void anOffTimeFromMiddayIsNeverAfterMidnight() {
        assertThat(LegacyCalendarConverter.offNextDay(LocalTime.of(1, 0), LocalTime.of(6, 30))).isTrue();
        assertThat(LegacyCalendarConverter.offNextDay(LocalTime.of(16, 15), LocalTime.of(16, 30))).isFalse();
        assertThat(LegacyCalendarConverter.offNextDay(LocalTime.of(22, 30), LocalTime.of(6, 30))).isFalse();
    }

    @Test
    void anOffWindowThatNeverEndsLeavesNoRows() {
        // Off 00:00 -> 00:00 the next day on every day: never allowed, and not "no rules".
        assertThat(LegacyCalendarConverter.toAllowedRows(List.of(rule("00:00", "00:00",
                DayOfWeek.values())))).isEmpty();
    }

    @Test
    void noRulesMeansNoRows() {
        assertThat(LegacyCalendarConverter.toAllowedRows(List.of())).isEmpty();
    }

    @Test
    void overrideShiftIsTheNumberOfDaysUntilTheDeviceIsAllowedOnAgain() {
        assertThat(LegacyCalendarConverter.onDayShift(LocalTime.of(22, 30), LocalTime.of(6, 30))).isEqualTo(1);
        assertThat(LegacyCalendarConverter.onDayShift(LocalTime.of(1, 0), LocalTime.of(6, 30))).isEqualTo(1);
        assertThat(LegacyCalendarConverter.onDayShift(LocalTime.of(16, 15), LocalTime.of(16, 30))).isEqualTo(0);
    }

    @Test
    void anOverrideThatCrossedMidnightBecomesTwoWindowsAndOneThatDidNotStaysOne() {
        // Old: off 01:00 (after midnight) on 06:30. Allowed from 06:30 until the next off, 01:00 the following morning.
        assertThat(LegacyCalendarConverter.overrideWindows(LocalTime.of(1, 0), LocalTime.of(6, 30))).containsExactly(
                new LegacyCalendarConverter.OverrideWindow(0, LocalTime.of(6, 30), LocalTime.MIDNIGHT),
                new LegacyCalendarConverter.OverrideWindow(1, LocalTime.MIDNIGHT, LocalTime.of(1, 0)));
        // Old: off 22:30 on 06:30. Allowed 06:30..22:30 the same day.
        assertThat(LegacyCalendarConverter.overrideWindows(LocalTime.of(22, 30), LocalTime.of(6, 30))).containsExactly(
                new LegacyCalendarConverter.OverrideWindow(0, LocalTime.of(6, 30), LocalTime.of(22, 30)));
        // Off exactly at midnight: no spill into the next day.
        assertThat(LegacyCalendarConverter.overrideWindows(LocalTime.MIDNIGHT, LocalTime.of(6, 30))).containsExactly(
                new LegacyCalendarConverter.OverrideWindow(0, LocalTime.of(6, 30), LocalTime.MIDNIGHT));
    }

    // ---- the converted rows must behave like the old rules did

    /** The old model's off windows, built independently of the converter's minute-of-week arithmetic. */
    private static List<OffWindow> oldOffWindows(java.time.ZoneId zone, List<LegacyRule> rules, java.time.LocalDate first, java.time.LocalDate last) {
        List<OffWindow> windows = new java.util.ArrayList<>();
        for (java.time.LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            for (LegacyRule r : rules) {
                if (!r.days().contains(day.getDayOfWeek())) {
                    continue;
                }
                java.time.LocalDate offDate = LegacyCalendarConverter.offNextDay(r.off(), r.on()) ? day.plusDays(1) : day;
                java.time.LocalDate onDate = r.on().isAfter(r.off()) ? offDate : offDate.plusDays(1);
                windows.add(new OffWindow(offDate.atTime(r.off()).atZone(zone).toInstant(), onDate.atTime(r.on()).atZone(zone).toInstant()));
            }
        }
        windows.sort(java.util.Comparator.comparing(OffWindow::offAt));
        List<OffWindow> merged = new java.util.ArrayList<>();
        for (OffWindow w : windows) {
            if (!merged.isEmpty() && !w.offAt().isAfter(merged.get(merged.size() - 1).onAt())) {
                OffWindow prev = merged.remove(merged.size() - 1);
                merged.add(new OffWindow(prev.offAt(), w.onAt().isAfter(prev.onAt()) ? w.onAt() : prev.onAt()));
            } else {
                merged.add(w);
            }
        }
        return merged;
    }

    private static void assertSameBehaviour(List<LegacyRule> oldRules) {
        java.time.ZoneId zone = java.time.ZoneId.of("Europe/Dublin");
        java.util.UUID cal = java.util.UUID.randomUUID();
        List<CalendarRule> converted = LegacyCalendarConverter.toAllowedRows(oldRules).stream()
                .map(r -> new CalendarRule(java.util.UUID.randomUUID(), cal, new java.util.ArrayList<>(r.days()), r.start(), r.end())).toList();

        java.time.LocalDate first = java.time.LocalDate.of(2026, 10, 19);
        // Compare the middle of a three-week span; the edges are clipped differently by design.
        java.time.Instant from = java.time.LocalDate.of(2026, 10, 26).atStartOfDay(zone).toInstant();
        java.time.Instant to = java.time.LocalDate.of(2026, 11, 9).atStartOfDay(zone).toInstant();
        List<OffWindow> expected = oldOffWindows(zone, oldRules, first, java.time.LocalDate.of(2026, 11, 16)).stream()
                .filter(w -> w.onAt().isAfter(from) && w.offAt().isBefore(to)).toList();
        List<OffWindow> actual = CalendarResolver.offWindows(zone, converted, List.of(), from, to);

        // Trim both to [from, to) so the comparison is about behaviour, not where a span was cut off.
        java.util.function.Function<OffWindow, OffWindow> clip = w -> new OffWindow(
                w.offAt().isBefore(from) ? from : w.offAt(), w.onAt().isAfter(to) ? to : w.onAt());
        assertThat(actual.stream().map(clip).toList()).isEqualTo(expected.stream().map(clip).toList());
    }

    @Test
    void convertedSchoolNightsOffWindowsMatchTheOldOnes() {
        assertSameBehaviour(List.of(
                rule("22:30", "06:30", DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
                rule("01:00", "06:30", DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)));
    }

    @Test
    void convertedSameDayAndPartialWeekCalendarsMatchTheOldOnes() {
        assertSameBehaviour(List.of(rule("16:15", "16:30", DayOfWeek.THURSDAY)));
        assertSameBehaviour(List.of(
                rule("23:00", "07:00", DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
                rule("12:00", "13:00", DayOfWeek.SATURDAY),
                rule("02:00", "09:00", DayOfWeek.SUNDAY)));
    }
}
