package com.timelord.controller.schedule;

import com.timelord.controller.schedule.ScheduleDto.Rule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** Create/replace a schedule's identity and its weekly base rules (overrides are managed separately). */
public record ScheduleRequest(
        @NotBlank String name,
        @NotBlank String timezone,
        @NotNull ScheduleAction action,
        @NotNull List<@Valid @NotNull Rule> rules
) {
}
