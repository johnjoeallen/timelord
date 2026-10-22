package com.timelord.controller.calendar;

import jakarta.validation.constraints.NotNull;

/** Switches an override on or off without deleting it. */
public record EnabledRequest(@NotNull Boolean enabled) {
}
