package com.timelord.controller.calendar;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;

/** A single day is the same {@code startDate} and {@code endDate}. */
public record OverrideRequest(
        String name,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        @NotNull LocalTime offTime,
        @NotNull LocalTime onTime
) {
}
