package com.timelord.controller.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CalendarResolverTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Dublin");
    private static final UUID CAL = UUID.randomUUID();
    private static final DayOfWeek[] EVERY_DAY = DayOfWeek.values();

    /** 2026-10-26 is a Monday. */
    private static Instant at(String date, String time) {
        return ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), ZONE).toInstant();
    }

    private static CalendarRule row(String start, String end, DayOfWeek... days) {
        return new CalendarRule(UUID.randomUUID(), CAL, Arrays.asList(days), LocalTime.parse(start), LocalTime.parse(end));
    }

    private static CalendarOverride override(String from, String to, String start, String end) {
        return new CalendarOverride(UUID.randomUUID(), CAL, null, LocalDate.parse(from), LocalDate.parse(to),
                LocalTime.parse(start), LocalTime.parse(end), Instant.EPOCH);
    }

    private static List<AllowedWindow> allowed(List<CalendarRule> rules, List<CalendarOverride> overrides, String from, String to) {
        return CalendarResolver.allowedWindows(ZONE, rules, overrides, at(from.substring(0, 10), from.substring(11)),
                at(to.substring(0, 10), to.substring(11)));
    }

    private static AllowedWindow on(String date, String from, String toDate, String to) {
        return new AllowedWindow(at(date, from), at(toDate, to));
    }

    // ---- several rows on the same day

    @Test
    void severalRowsOnTheSameDayEachGiveAWindow() {
        List<CalendarRule> rules = List.of(
                row("08:30", "10:30", DayOfWeek.MONDAY),
                row("18:30", "23:30", DayOfWeek.MONDAY));

        List<AllowedWindow> w = allowed(rules, List.of(), "2026-10-26 00:00", "2026-10-26 23:59");

        assertThat(w).containsExactly(
                on("2026-10-26", "08:30", "2026-10-26", "10:30"),
                on("2026-10-26", "18:30", "2026-10-26", "23:30"));
    }

    @Test
    void theDeviceIsOffBetweenAndOutsideTheWindows() {
        List<CalendarRule> rules = List.of(
                row("08:30", "10:30", EVERY_DAY),
                row("18:30", "23:30", EVERY_DAY));

        List<OffWindow> off = CalendarResolver.offWindows(ZONE, rules, List.of(), at("2026-10-26", "12:00"), at("2026-10-26", "13:00"));

        // The gap containing midday, between the two windows.
        assertThat(off).containsExactly(new OffWindow(at("2026-10-26", "10:30"), at("2026-10-26", "18:30")));
    }

    @Test
    void overnightGapRunsFromTheLastWindowOfOneDayToTheFirstOfTheNext() {
        List<CalendarRule> rules = List.of(
                row("08:30", "10:30", EVERY_DAY),
                row("18:30", "23:30", EVERY_DAY));

        List<OffWindow> off = CalendarResolver.offWindows(ZONE, rules, List.of(), at("2026-10-26", "23:45"), at("2026-10-27", "07:00"));

        assertThat(off).containsExactly(new OffWindow(at("2026-10-26", "23:30"), at("2026-10-27", "08:30")));
    }

    @Test
    void touchingAndOverlappingRowsMergeIntoOneWindow() {
        List<CalendarRule> rules = List.of(
                row("08:00", "12:00", DayOfWeek.MONDAY),
                row("12:00", "13:00", DayOfWeek.MONDAY),
                row("12:30", "14:00", DayOfWeek.MONDAY));

        assertThat(allowed(rules, List.of(), "2026-10-26 00:00", "2026-10-26 23:59"))
                .containsExactly(on("2026-10-26", "08:00", "2026-10-26", "14:00"));
    }

    // ---- window shapes: every day is its own midnight-to-midnight line

    @Test
    void anOffWindowInsideADayIsTwoOnWindowsAroundIt() {
        // "Off 16:15, on 16:30" on a Thursday: on midnight..16:15, off, on again 16:30..midnight.
        // 2026-10-22 is a Thursday.
        List<CalendarRule> rules = List.of(row("00:00", "16:15", DayOfWeek.THURSDAY), row("16:30", "00:00", DayOfWeek.THURSDAY));

        List<AllowedWindow> allowed = allowed(rules, List.of(), "2026-10-22 00:00", "2026-10-22 23:59");
        List<OffWindow> off = CalendarResolver.offWindows(ZONE, rules, List.of(), at("2026-10-22", "09:00"), at("2026-10-22", "20:00"));

        assertThat(allowed).containsExactly(on("2026-10-22", "00:00", "2026-10-22", "16:15"), on("2026-10-22", "16:30", "2026-10-23", "00:00"));
        assertThat(off).contains(new OffWindow(at("2026-10-22", "16:15"), at("2026-10-22", "16:30")));
    }

    @Test
    void aWindowEndingAtMidnightMeetsTheNextDaysWindowStartingAtMidnight() {
        // Friday evening 18:30..midnight, Saturday small hours 00:00..01:00: one continuous window.
        List<CalendarRule> rules = List.of(row("18:30", "00:00", DayOfWeek.FRIDAY), row("00:00", "01:00", DayOfWeek.SATURDAY));

        assertThat(allowed(rules, List.of(), "2026-10-23 12:00", "2026-10-24 12:00"))
                .containsExactly(on("2026-10-23", "18:30", "2026-10-24", "01:00"));
        // ...and no gap at midnight, so nothing to sleep for between them.
        assertThat(CalendarResolver.offWindows(ZONE, rules, List.of(), at("2026-10-23", "20:00"), at("2026-10-24", "00:30")))
                .noneMatch(g -> g.offAt().isBefore(at("2026-10-24", "00:30")) && g.onAt().isAfter(at("2026-10-23", "23:00")));
    }

    @Test
    void anEndOfMidnightMeansTheEndOfTheDayAndSoZeroToZeroIsTheWholeDay() {
        assertThat(allowed(List.of(row("18:30", "00:00", DayOfWeek.MONDAY)), List.of(), "2026-10-26 00:00", "2026-10-27 23:59"))
                .containsExactly(on("2026-10-26", "18:30", "2026-10-27", "00:00"));
        assertThat(allowed(List.of(row("00:00", "00:00", DayOfWeek.SATURDAY)), List.of(), "2026-10-24 00:00", "2026-10-24 23:59"))
                .containsExactly(on("2026-10-24", "00:00", "2026-10-25", "00:00"));
    }

    @Test
    void aWindowThatWouldRunPastMidnightIsNotAWindow() {
        assertThat(CalendarResolver.isValidWindow(LocalTime.of(18, 30), LocalTime.of(1, 0))).isFalse();
        assertThat(CalendarResolver.isValidWindow(LocalTime.of(8, 0), LocalTime.of(8, 0))).isFalse();
        assertThat(CalendarResolver.isValidWindow(LocalTime.of(8, 0), LocalTime.of(9, 0))).isTrue();
        assertThat(CalendarResolver.isValidWindow(LocalTime.of(18, 30), LocalTime.MIDNIGHT)).isTrue();
        assertThat(allowed(List.of(row("18:30", "01:00", DayOfWeek.FRIDAY)), List.of(), "2026-10-23 00:00", "2026-10-24 23:59")).isEmpty();
    }

    @Test
    void consecutiveWholeDaysMergeIntoOneLongWindow() {
        List<AllowedWindow> w = allowed(List.of(row("00:00", "00:00", DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)), List.of(),
                "2026-10-24 00:00", "2026-10-25 23:59");

        assertThat(w).containsExactly(on("2026-10-24", "00:00", "2026-10-26", "00:00"));
    }

    @Test
    void aDayWithNoRowIsOffAllDayWhenOtherDaysHaveRows() {
        // Only weekdays have rows; look at a Saturday.
        List<CalendarRule> weekdays = List.of(row("08:00", "20:00",
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY));

        List<OffWindow> off = CalendarResolver.offWindows(ZONE, weekdays, List.of(), at("2026-10-24", "12:00"), at("2026-10-27", "00:00"));

        // Friday 20:00 until Monday 08:00.
        assertThat(off).hasSizeGreaterThanOrEqualTo(1);
        assertThat(off.get(0).offAt()).isEqualTo(at("2026-10-23", "20:00"));
        assertThat(off.get(0).onAt()).isEqualTo(at("2026-10-26", "08:00"));
    }

    @Test
    void aCalendarWithNoRowsRestrictsNothing() {
        assertThat(CalendarResolver.offWindows(ZONE, List.of(), List.of(), at("2026-10-26", "00:00"), at("2026-10-30", "00:00"))).isEmpty();
        // Even with an override: there is nothing for it to extend.
        assertThat(CalendarResolver.offWindows(ZONE, List.of(), List.of(override("2026-10-26", "2026-10-27", "08:00", "09:00")),
                at("2026-10-26", "00:00"), at("2026-10-30", "00:00"))).isEmpty();
    }

    // ---- overrides are layered on afterwards: they extend, never narrow

    @Test
    void anOverrideCanExtendAWindow() {
        List<CalendarRule> rules = List.of(row("18:30", "23:30", EVERY_DAY));
        // Christmas Eve runs late: add 23:30..midnight, then 00:00..01:00 on the 25th (a window can't wrap).
        List<CalendarOverride> late = List.of(override("2026-12-24", "2026-12-24", "23:30", "00:00"),
                override("2026-12-25", "2026-12-25", "00:00", "01:00"));

        List<AllowedWindow> w = allowed(rules, late, "2026-12-24 12:00", "2026-12-25 12:00");

        assertThat(w).containsExactly(on("2026-12-24", "18:30", "2026-12-25", "01:00"));
    }

    @Test
    void anOverrideInsideAnExistingWindowChangesNothing() {
        List<CalendarRule> rules = List.of(row("18:30", "23:30", EVERY_DAY));
        CalendarOverride narrower = override("2026-10-26", "2026-10-26", "19:00", "20:00");

        assertThat(allowed(rules, List.of(narrower), "2026-10-26 00:00", "2026-10-26 23:59"))
                .isEqualTo(allowed(rules, List.of(), "2026-10-26 00:00", "2026-10-26 23:59"));
    }

    @Test
    void anOverrideCanAddAWindowWhereTheWeeklyRowsHaveNone() {
        List<CalendarRule> weekdays = List.of(row("08:00", "20:00", DayOfWeek.MONDAY));
        CalendarOverride bonus = override("2026-10-27", "2026-10-27", "10:00", "12:00");

        List<AllowedWindow> w = allowed(weekdays, List.of(bonus), "2026-10-26 00:00", "2026-10-27 23:59");

        assertThat(w).containsExactly(on("2026-10-26", "08:00", "2026-10-26", "20:00"), on("2026-10-27", "10:00", "2026-10-27", "12:00"));
    }

    @Test
    void overridesStackAndEachCoversItsOwnDateRange() {
        List<CalendarRule> rules = List.of(row("18:00", "20:00", EVERY_DAY));
        List<CalendarOverride> overrides = List.of(
                override("2026-10-26", "2026-10-28", "08:00", "09:00"),
                override("2026-10-27", "2026-10-27", "12:00", "13:00"));

        List<AllowedWindow> w = allowed(rules, overrides, "2026-10-27 00:00", "2026-10-27 23:59");

        assertThat(w).containsExactly(
                on("2026-10-27", "08:00", "2026-10-27", "09:00"),
                on("2026-10-27", "12:00", "2026-10-27", "13:00"),
                on("2026-10-27", "18:00", "2026-10-27", "20:00"));
    }

    // ---- the off windows the agent enforces

    @Test
    void offWindowsStartAtAStableInstantNotAtTheQueryTime() {
        List<CalendarRule> rules = List.of(row("08:00", "20:00", EVERY_DAY));

        List<OffWindow> early = CalendarResolver.offWindows(ZONE, rules, List.of(), at("2026-10-26", "03:00"), at("2026-10-27", "00:00"));
        List<OffWindow> later = CalendarResolver.offWindows(ZONE, rules, List.of(), at("2026-10-26", "05:00"), at("2026-10-27", "00:00"));

        // Same answer for the same day, otherwise the agent's revision would change on every poll.
        assertThat(early).isEqualTo(later);
        assertThat(early.get(0).offAt()).isBefore(at("2026-10-26", "00:00"));
    }

    @Test
    void windowsAroundADaylightSavingChangeResolveToRealInstants() {
        // Clocks go forward 01:00 -> 02:00 on 2026-03-29 in Dublin.
        List<AllowedWindow> w = allowed(List.of(row("00:30", "03:30", DayOfWeek.SUNDAY)), List.of(), "2026-03-29 00:00", "2026-03-29 23:59");

        assertThat(w).hasSize(1);
        assertThat(java.time.Duration.between(w.get(0).onAt(), w.get(0).offAt())).isEqualTo(java.time.Duration.ofHours(2));
    }
}
