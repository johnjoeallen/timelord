package com.timelord.controller.calendar;

import java.time.Instant;

/** A concrete span, [onAt, offAt), during which the device is allowed to be on. */
public record AllowedWindow(Instant onAt, Instant offAt) {
}
