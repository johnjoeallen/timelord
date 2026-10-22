package com.timelord.controller.schedule;

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

class ScheduleResolverTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Dublin");
    private static final UUID CAL = UUID.randomUUID();
    private static final DayOfWeek[] EVERY_DAY = DayOfWeek.values();

    /** 2026-10-26 is a Monday. */
    private static Instant at(String date, String time) {
        return ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), ZONE).toInstant();
    }

    private static ScheduleRule row(String start, String end, DayOfWeek... days) {
        return new ScheduleRule(UUID.randomUUID(), CAL, Arrays.asList(days), LocalTime.parse(start), LocalTime.parse(end));
    }

    private static ScheduleOverride override(String from, String to, String start, String end) {
        return new ScheduleOverride(UUID.randomUUID(), CAL, null, LocalDate.parse(from), LocalDate.parse(to),
                LocalTime.parse(start), LocalTime.parse(end), Instant.EPOCH);
    }

    private static List<AllowedWindow> allowed(List<ScheduleRule> rules, List<ScheduleOverride> overrides, String from, String to) {
        return ScheduleResolver.allowedWindows(ZONE, rules, overrides, at(from.substring(0, 10), from.substring(11)),
                at(to.substring(0, 10), to.substring(11)));
    }

    private static AllowedWindow on(String date, String from, String toDate, String to) {
        return new AllowedWindow(at(date, from), at(toDate, to));
    }

    // ---- several rows on the same day

    @Test
    void severalRowsOnTheSameDayEachGiveAWindow() {
        List<ScheduleRule> rules = List.of(
                row("08:30", "10:30", DayOfWeek.MONDAY),
                row("18:30", "23:30", DayOfWeek.MONDAY));

        List<AllowedWindow> w = allowed(rules, List.of(), "2026-10-26 00:00", "2026-10-26 23:59");

        assertThat(w).containsExactly(
                on("2026-10-26", "08:30", "2026-10-26", "10:30"),
                on("2026-10-26", "18:30", "2026-10-26", "23:30"));
    }

    @Test
    void theDeviceIsOffBetweenAndOutsideTheWindows() {
        List<ScheduleRule> rules = List.of(
                row("08:30", "10:30", EVERY_DAY),
                row("18:30", "23:30", EVERY_DAY));

        List<OffWindow> off = ScheduleResolver.offWindows(ZONE, rules, List.of(), at("2026-10-26", "12:00"), at("2026-10-26", "13:00"));

        // The gap containing midday, between the two windows.
        assertThat(off).containsExactly(new OffWindow(at("2026-10-26", "10:30"), at("2026-10-26", "18:30")));
    }

    @Test
    void overnightGapRunsFromTheLastWindowOfOneDayToTheFirstOfTheNext() {
        List<ScheduleRule> rules = List.of(
                row("08:30", "10:30", EVERY_DAY),
                row("18:30", "23:30", EVERY_DAY));

        List<OffWindow> off = ScheduleResolver.offWindows(ZONE, rules, List.of(), at("2026-10-26", "23:45"), at("2026-10-27", "07:00"));

        assertThat(off).containsExactly(new OffWindow(at("2026-10-26", "23:30"), at("2026-10-27", "08:30")));
    }

    @Test
    void touchingAndOverlappingRowsMergeIntoOneWindow() {
        List<ScheduleRule> rules = List.of(
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
        List<ScheduleRule> rules = List.of(row("00:00", "16:15", DayOfWeek.THURSDAY), row("16:30", "00:00", DayOfWeek.THURSDAY));

        List<AllowedWindow> allowed = allowed(rules, List.of(), "2026-10-22 00:00", "2026-10-22 23:59");
        List<OffWindow> off = ScheduleResolver.offWindows(ZONE, rules, List.of(), at("2026-10-22", "09:00"), at("2026-10-22", "20:00"));

        assertThat(allowed).containsExactly(on("2026-10-22", "00:00", "2026-10-22", "16:15"), on("2026-10-22", "16:30", "2026-10-23", "00:00"));
        assertThat(off).contains(new OffWindow(at("2026-10-22", "16:15"), at("2026-10-22", "16:30")));
    }

    @Test
    void aWindowEndingAtMidnightMeetsTheNextDaysWindowStartingAtMidnight() {
        // Friday evening 18:30..midnight, Saturday small hours 00:00..01:00: one continuous window.
        List<ScheduleRule> rules = List.of(row("18:30", "00:00", DayOfWeek.FRIDAY), row("00:00", "01:00", DayOfWeek.SATURDAY));

        assertThat(allowed(rules, List.of(), "2026-10-23 12:00", "2026-10-24 12:00"))
                .containsExactly(on("2026-10-23", "18:30", "2026-10-24", "01:00"));
        // ...and no gap at midnight, so nothing to sleep for between them.
        assertThat(ScheduleResolver.offWindows(ZONE, rules, List.of(), at("2026-10-23", "20:00"), at("2026-10-24", "00:30")))
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
        assertThat(ScheduleResolver.isValidWindow(LocalTime.of(18, 30), LocalTime.of(1, 0))).isFalse();
        assertThat(ScheduleResolver.isValidWindow(LocalTime.of(8, 0), LocalTime.of(8, 0))).isFalse();
        assertThat(ScheduleResolver.isValidWindow(LocalTime.of(8, 0), LocalTime.of(9, 0))).isTrue();
        assertThat(ScheduleResolver.isValidWindow(LocalTime.of(18, 30), LocalTime.MIDNIGHT)).isTrue();
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
        List<ScheduleRule> weekdays = List.of(row("08:00", "20:00",
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY));

        List<OffWindow> off = ScheduleResolver.offWindows(ZONE, weekdays, List.of(), at("2026-10-24", "12:00"), at("2026-10-27", "00:00"));

        // Friday 20:00 until Monday 08:00.
        assertThat(off).hasSizeGreaterThanOrEqualTo(1);
        assertThat(off.get(0).offAt()).isEqualTo(at("2026-10-23", "20:00"));
        assertThat(off.get(0).onAt()).isEqualTo(at("2026-10-26", "08:00"));
    }

    @Test
    void aScheduleWithNoRowsRestrictsNothing() {
        assertThat(ScheduleResolver.offWindows(ZONE, List.of(), List.of(), at("2026-10-26", "00:00"), at("2026-10-30", "00:00"))).isEmpty();
        // Even with an override: there is nothing for it to extend.
        assertThat(ScheduleResolver.offWindows(ZONE, List.of(), List.of(override("2026-10-26", "2026-10-27", "08:00", "09:00")),
                at("2026-10-26", "00:00"), at("2026-10-30", "00:00"))).isEmpty();
    }

    // ---- overrides are layered on afterwards: they extend, never narrow

    @Test
    void anOverrideCanExtendAWindow() {
        List<ScheduleRule> rules = List.of(row("18:30", "23:30", EVERY_DAY));
        // Christmas Eve runs late: add 23:30..midnight, then 00:00..01:00 on the 25th (a window can't wrap).
        List<ScheduleOverride> late = List.of(override("2026-12-24", "2026-12-24", "23:30", "00:00"),
                override("2026-12-25", "2026-12-25", "00:00", "01:00"));

        List<AllowedWindow> w = allowed(rules, late, "2026-12-24 12:00", "2026-12-25 12:00");

        assertThat(w).containsExactly(on("2026-12-24", "18:30", "2026-12-25", "01:00"));
    }

    @Test
    void anOverrideInsideAnExistingWindowChangesNothing() {
        List<ScheduleRule> rules = List.of(row("18:30", "23:30", EVERY_DAY));
        ScheduleOverride narrower = override("2026-10-26", "2026-10-26", "19:00", "20:00");

        assertThat(allowed(rules, List.of(narrower), "2026-10-26 00:00", "2026-10-26 23:59"))
                .isEqualTo(allowed(rules, List.of(), "2026-10-26 00:00", "2026-10-26 23:59"));
    }

    @Test
    void anOverrideCanAddAWindowWhereTheWeeklyRowsHaveNone() {
        List<ScheduleRule> weekdays = List.of(row("08:00", "20:00", DayOfWeek.MONDAY));
        ScheduleOverride bonus = override("2026-10-27", "2026-10-27", "10:00", "12:00");

        List<AllowedWindow> w = allowed(weekdays, List.of(bonus), "2026-10-26 00:00", "2026-10-27 23:59");

        assertThat(w).containsExactly(on("2026-10-26", "08:00", "2026-10-26", "20:00"), on("2026-10-27", "10:00", "2026-10-27", "12:00"));
    }

    @Test
    void overridesStackAndEachCoversItsOwnDateRange() {
        List<ScheduleRule> rules = List.of(row("18:00", "20:00", EVERY_DAY));
        List<ScheduleOverride> overrides = List.of(
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
        List<ScheduleRule> rules = List.of(row("08:00", "20:00", EVERY_DAY));

        List<OffWindow> early = ScheduleResolver.offWindows(ZONE, rules, List.of(), at("2026-10-26", "03:00"), at("2026-10-27", "00:00"));
        List<OffWindow> later = ScheduleResolver.offWindows(ZONE, rules, List.of(), at("2026-10-26", "05:00"), at("2026-10-27", "00:00"));

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

    // ---- local wall time -> UTC instants (the agent only ever sees instants)

    @Test
    void offWindowEdgesAreUtcInstantsNotLocalTimes() {
        // Dublin is UTC+1 on 2026-10-22 (summer time): on 08:00-17:55 local means off from 16:55Z.
        List<ScheduleRule> rules = List.of(row("08:00", "17:55", DayOfWeek.THURSDAY), row("18:00", "00:00", DayOfWeek.THURSDAY));

        List<OffWindow> off = ScheduleResolver.offWindows(ZONE, rules, List.of(), at("2026-10-22", "00:00"), at("2026-10-23", "00:00"));

        OffWindow gap = off.stream().filter(w -> w.offAt().equals(Instant.parse("2026-10-22T16:55:00Z"))).findFirst().orElseThrow();
        assertThat(gap.onAt()).isEqualTo(Instant.parse("2026-10-22T17:00:00Z"));
    }

    @Test
    void theSameLocalTimeIsADifferentInstantAcrossTheOctoberChange() {
        // Clocks go back at 02:00 on Sunday 2026-10-25: Thursday 17:55 is 16:55Z, the following Thursday 17:55Z.
        ScheduleRule r = row("08:00", "17:55", DayOfWeek.THURSDAY);

        List<AllowedWindow> before = allowed(List.of(r), List.of(), "2026-10-22 00:00", "2026-10-22 23:59");
        List<AllowedWindow> after = allowed(List.of(r), List.of(), "2026-10-29 00:00", "2026-10-29 23:59");

        assertThat(before.get(0).offAt()).isEqualTo(Instant.parse("2026-10-22T16:55:00Z"));
        assertThat(after.get(0).offAt()).isEqualTo(Instant.parse("2026-10-29T17:55:00Z"));
    }

    @Test
    void aSaturdayNightWindowAcrossTheClockChangeUsesRealOffsets() {
        // Sat 18:00 BST (17:00Z) to Sun 03:00 GMT (03:00Z): ten real hours, not nine.
        List<ScheduleRule> rules = List.of(row("18:00", "00:00", DayOfWeek.SATURDAY), row("00:00", "03:00", DayOfWeek.SUNDAY));

        List<AllowedWindow> w = allowed(rules, List.of(), "2026-10-24 12:00", "2026-10-25 12:00");

        assertThat(w).containsExactly(new AllowedWindow(Instant.parse("2026-10-24T17:00:00Z"), Instant.parse("2026-10-25T03:00:00Z")));
    }

    @Test
    void anAmbiguousLocalTimeResolvesWithoutFailing() {
        // 01:30 happens twice on 2026-10-25 in Dublin; resolution must not throw and must stay ordered.
        List<AllowedWindow> w = allowed(List.of(row("01:30", "02:30", DayOfWeek.SUNDAY)), List.of(), "2026-10-25 00:00", "2026-10-25 23:59");

        assertThat(w).hasSize(1);
        assertThat(w.get(0).offAt()).isAfter(w.get(0).onAt());
    }

    @Test
    void thursdayWithTwoRowsSleepsOnlyInTheGapBetweenThem() {
        // Thu 22 Oct 2026 (UTC+1): on 16:55-17:55 and 18:35-23:00 local; asleep 17:55-18:35 and outside both.
        List<ScheduleRule> rules = List.of(row("16:55", "17:55", DayOfWeek.THURSDAY), row("18:35", "23:00", DayOfWeek.THURSDAY));

        List<OffWindow> off = ScheduleResolver.offWindows(ZONE, rules, List.of(), at("2026-10-22", "12:00"), at("2026-10-22", "23:30"));

        assertThat(off).contains(new OffWindow(Instant.parse("2026-10-22T16:55:00Z"), Instant.parse("2026-10-22T17:35:00Z")));
        assertThat(off).doesNotContain(new OffWindow(Instant.parse("2026-10-22T15:55:00Z"), Instant.parse("2026-10-22T22:00:00Z")));
    }

    // ---- disabled rows

    @Test
    void aDisabledRowGivesNoWindow() {
        ScheduleRule off = new ScheduleRule(UUID.randomUUID(), CAL, List.of(DayOfWeek.MONDAY), LocalTime.of(18, 30),
                LocalTime.of(23, 30), false);

        List<AllowedWindow> w = allowed(List.of(row("08:30", "10:30", DayOfWeek.MONDAY), off), List.of(), "2026-10-26 00:00", "2026-10-26 23:59");

        assertThat(w).containsExactly(on("2026-10-26", "08:30", "2026-10-26", "10:30"));
    }

    @Test
    void aDisabledOverrideAddsNothing() {
        ScheduleOverride off = new ScheduleOverride(UUID.randomUUID(), CAL, null, LocalDate.parse("2026-10-26"),
                LocalDate.parse("2026-10-26"), LocalTime.of(20, 0), LocalTime.of(23, 0), Instant.EPOCH);
        off.setEnabled(false);

        List<AllowedWindow> w = allowed(List.of(row("08:30", "10:30", DayOfWeek.MONDAY)), List.of(off), "2026-10-26 00:00", "2026-10-26 23:59");

        assertThat(w).containsExactly(on("2026-10-26", "08:30", "2026-10-26", "10:30"));
    }

    @Test
    void aScheduleWhoseRowsAreAllDisabledRestrictsNothing() {
        ScheduleRule off = new ScheduleRule(UUID.randomUUID(), CAL, List.of(EVERY_DAY), LocalTime.of(8, 0),
                LocalTime.of(9, 0), false);

        assertThat(ScheduleResolver.offWindows(ZONE, List.of(off), List.of(), at("2026-10-26", "00:00"), at("2026-10-28", "00:00"))).isEmpty();
    }
}
