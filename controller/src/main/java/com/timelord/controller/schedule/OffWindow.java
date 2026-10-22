package com.timelord.controller.schedule;

import java.time.Instant;

/** A concrete span, [offAt, onAt), during which the device must not be on. */
public record OffWindow(Instant offAt, Instant onAt) {
}
