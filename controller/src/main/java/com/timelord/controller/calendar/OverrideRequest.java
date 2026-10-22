package com.timelord.controller.calendar;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;

/**

 * Adds an allowed window ({@code startTime} to {@code endTime} within the day; a missing start means the
 * start of the day, a missing end or 00:00 means midnight) on every date from {@code startDate} to {@code endDate}; a single day is the same start
 * and end. It extends the weekly schedule and cannot shorten it.
 */
public record OverrideRequest(
        String name,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        LocalTime startTime,
        LocalTime endTime
) {
}
