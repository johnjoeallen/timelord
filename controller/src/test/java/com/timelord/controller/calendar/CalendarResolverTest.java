package com.timelord.controller.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CalendarResolverTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Dublin");
    private static final UUID CAL = UUID.randomUUID();

    private static final List<CalendarRule> BASE = List.of(
            new CalendarRule(UUID.randomUUID(), CAL,
                    List.of(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
                    LocalTime.of(22, 30), LocalTime.of(6, 30)),
            new CalendarRule(UUID.randomUUID(), CAL, List.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY),
                    LocalTime.of(1, 0), LocalTime.of(6, 30)));

    private static Instant at(String date, String time) {
        return ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), ZONE).toInstant();
    }

    private static CalendarOverride override(String start, String end, String off, String on, Instant created) {
        return new CalendarOverride(UUID.randomUUID(), CAL, null, LocalDate.parse(start), LocalDate.parse(end),
                LocalTime.parse(off), LocalTime.parse(on), created);
    }

    @Test
    void weekdayNightStartsThatEvening() {
        // 2026-10-26 is a Monday.
        List<OffWindow> w = CalendarResolver.windows(ZONE, BASE, List.of(), at("2026-10-26", "12:00"), at("2026-10-27", "12:00"));

        assertThat(w).containsExactly(new OffWindow(at("2026-10-26", "22:30"), at("2026-10-27", "06:30")));
    }

    @Test
    void fridayNightGoesOffAfterMidnight() {
        // 2026-10-23 is a Friday: off at 01:00 Saturday, on at 06:30 Saturday.
        List<OffWindow> w = CalendarResolver.windows(ZONE, BASE, List.of(), at("2026-10-23", "12:00"), at("2026-10-24", "12:00"));

        assertThat(w).containsExactly(new OffWindow(at("2026-10-24", "01:00"), at("2026-10-24", "06:30")));
    }

    @Test
    void windowInProgressAtFromIsIncluded() {
        List<OffWindow> w = CalendarResolver.windows(ZONE, BASE, List.of(), at("2026-10-27", "03:00"), at("2026-10-27", "04:00"));

        assertThat(w).containsExactly(new OffWindow(at("2026-10-26", "22:30"), at("2026-10-27", "06:30")));
    }

    @Test
    void overrideReplacesBaseTimesWithoutTouchingOtherNights() {
        CalendarOverride holiday = override("2026-12-20", "2027-01-05", "01:00", "06:30", Instant.EPOCH);

        List<OffWindow> w = CalendarResolver.windows(ZONE, BASE, List.of(holiday),
                at("2026-12-20", "12:00"), at("2026-12-22", "12:00"));

        // Sun 20th and Mon 21st would be 22:30 off normally.
        assertThat(w).containsExactly(
                new OffWindow(at("2026-12-21", "01:00"), at("2026-12-21", "06:30")),
                new OffWindow(at("2026-12-22", "01:00"), at("2026-12-22", "06:30")));
    }

    @Test
    void singleDayOverrideOnlyAffectsThatNight() {
        CalendarOverride oneNight = override("2026-10-27", "2026-10-27", "23:30", "06:30", Instant.EPOCH);

        List<OffWindow> w = CalendarResolver.windows(ZONE, BASE, List.of(oneNight),
                at("2026-10-26", "12:00"), at("2026-10-29", "12:00"));

        assertThat(w).containsExactly(
                new OffWindow(at("2026-10-26", "22:30"), at("2026-10-27", "06:30")),
                new OffWindow(at("2026-10-27", "23:30"), at("2026-10-28", "06:30")),
                new OffWindow(at("2026-10-28", "22:30"), at("2026-10-29", "06:30")));
    }

    @Test
    void narrowestOverrideWinsThenLatestCreated() {
        CalendarOverride wide = override("2026-12-01", "2026-12-31", "01:00", "06:30", Instant.parse("2026-01-01T00:00:00Z"));
        CalendarOverride narrow = override("2026-12-24", "2026-12-24", "23:30", "08:00", Instant.parse("2025-01-01T00:00:00Z"));

        List<OffWindow> w = CalendarResolver.windows(ZONE, BASE, List.of(wide, narrow),
                at("2026-12-24", "12:00"), at("2026-12-25", "12:00"));

        assertThat(w).containsExactly(new OffWindow(at("2026-12-24", "23:30"), at("2026-12-25", "08:00")));
    }

    @Test
    void noRuleForADayMeansNoWindow() {
        List<CalendarRule> weekdaysOnly = List.of(
                new CalendarRule(UUID.randomUUID(), CAL, List.of(DayOfWeek.MONDAY), LocalTime.of(22, 0), LocalTime.of(6, 0)));

        List<OffWindow> w = CalendarResolver.windows(ZONE, weekdaysOnly, List.of(),
                at("2026-10-27", "12:00"), at("2026-10-29", "12:00"));

        assertThat(w).isEmpty();
    }

    @Test
    void overlappingNightsAreMerged() {
        // Monday night's override runs until 10:00 Tuesday, but Tuesday's night already starts at 09:00.
        CalendarOverride late = override("2026-10-26", "2026-10-26", "22:30", "10:00", Instant.EPOCH);
        List<CalendarRule> rules = List.of(
                new CalendarRule(UUID.randomUUID(), CAL, List.of(DayOfWeek.TUESDAY), LocalTime.of(9, 0), LocalTime.of(8, 0)));

        List<OffWindow> w = CalendarResolver.windows(ZONE, rules, List.of(late),
                at("2026-10-26", "12:00"), at("2026-10-28", "12:00"));

        assertThat(w).containsExactly(new OffWindow(at("2026-10-26", "22:30"), at("2026-10-28", "08:00")));
    }
}
