package com.timelord.controller.schedule;

import jakarta.validation.constraints.NotNull;

/** Switches an override on or off without deleting it. */
public record EnabledRequest(@NotNull Boolean enabled) {
}
