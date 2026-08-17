package com.timelord.controller.timezone;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * Exposed to Thymeleaf templates as {@code @timeFormat} (Spring bean
 * reference via SpEL — no extra Thymeleaf dialect required). Converts the
 * UTC {@link Instant} values stored throughout the domain model to the
 * viewing browser's local time, resolved per-request by
 * {@link RequestZoneResolver} and made available to every view as the
 * {@code zone} model attribute.
 *
 * All storage and business logic (agent, protocol, DB, event ordering)
 * stays in UTC/Instant — this is a display-only conversion at the template
 * boundary.
 */
@Component("timeFormat")
public class TimeFormat {

    private static final DateTimeFormatter DEFAULT_PATTERN = DateTimeFormatter.ofPattern("MMM d, HH:mm");

    public String format(Instant instant, ZoneId zone) {
        if (instant == null) {
            return null;
        }
        return DEFAULT_PATTERN.withZone(zone).format(instant);
    }

    public String format(Instant instant, ZoneId zone, String pattern) {
        if (instant == null) {
            return null;
        }
        return DateTimeFormatter.ofPattern(pattern).withZone(zone).format(instant);
    }
}
