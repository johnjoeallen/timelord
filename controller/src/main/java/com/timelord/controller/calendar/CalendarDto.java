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

    /**
     * Allowed from {@code startTime} to {@code endTime} on each of {@code days}, within the day. A missing
     * {@code startTime} means the start of the day and a missing {@code endTime} means midnight at the end
     * of it (stored as 00:00), so 00:00 to 00:00, or both left out, is the whole day. Responses always
     * carry both. {@code enabled} may be left out (it means true); a disabled row is kept but ignored.
     */
    public record Rule(List<DayOfWeek> days, LocalTime startTime, LocalTime endTime, Boolean enabled) {
        public Rule {
            if (enabled == null) {
                enabled = true;
            }
        }

        public Rule(List<DayOfWeek> days, LocalTime startTime, LocalTime endTime) {
            this(days, startTime, endTime, true);
        }

        static Rule from(CalendarRule r) {
            return new Rule(r.getDays(), r.getStartTime(), r.getEndTime(), r.isEnabled());
        }
    }

    public record Override(UUID id, String name, LocalDate startDate, LocalDate endDate,
                           LocalTime startTime, LocalTime endTime, boolean enabled) {
        static Override from(CalendarOverride o) {
            return new Override(o.getId(), o.getName(), o.getStartDate(), o.getEndDate(), o.getStartTime(),
                    o.getEndTime(), o.isEnabled());
        }
    }

    static CalendarDto from(Calendar c, List<CalendarRule> rules, List<CalendarOverride> overrides) {
        return new CalendarDto(c.getId(), c.getName(), c.getTimezone(), c.getAction(),
                rules.stream().map(Rule::from).toList(),
                overrides.stream().map(Override::from).toList(),
                c.getCreatedAt(), c.getUpdatedAt());
    }
}
