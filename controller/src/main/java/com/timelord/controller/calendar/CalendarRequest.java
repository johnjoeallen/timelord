package com.timelord.controller.calendar;

import com.timelord.controller.calendar.CalendarDto.Rule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** Create/replace a calendar's identity and its weekly base rules (overrides are managed separately). */
public record CalendarRequest(
        @NotBlank String name,
        @NotBlank String timezone,
        @NotNull CalendarAction action,
        @NotNull List<@Valid @NotNull Rule> rules
) {
}
