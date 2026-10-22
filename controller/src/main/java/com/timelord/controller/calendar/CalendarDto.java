package com.timelord.controller.calendar;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record CalendarDto(
        UUID id,
        String name,
        String timezone,
        CalendarAction action,
        List<Rule> rules,
        List<Override> overrides,
        Instant createdAt,
        Instant updatedAt
) {

    public record Rule(List<DayOfWeek> days, LocalTime offTime, LocalTime onTime) {
        static Rule from(CalendarRule r) {
            return new Rule(r.getDays(), r.getOffTime(), r.getOnTime());
        }
    }

    public record Override(UUID id, String name, LocalDate startDate, LocalDate endDate,
                           LocalTime offTime, LocalTime onTime) {
        static Override from(CalendarOverride o) {
            return new Override(o.getId(), o.getName(), o.getStartDate(), o.getEndDate(), o.getOffTime(), o.getOnTime());
        }
    }

    static CalendarDto from(Calendar c, List<CalendarRule> rules, List<CalendarOverride> overrides) {
        return new CalendarDto(c.getId(), c.getName(), c.getTimezone(), c.getAction(),
                rules.stream().map(Rule::from).toList(),
                overrides.stream().map(Override::from).toList(),
                c.getCreatedAt(), c.getUpdatedAt());
    }
}
