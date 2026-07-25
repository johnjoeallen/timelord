package com.timelord.controller.agent;

import com.timelord.controller.event.EventSeverity;
import com.timelord.controller.event.EventSource;
import com.timelord.controller.event.EventType;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record EventItem(
        @NotNull UUID eventId,
        @NotNull EventType eventType,
        @NotNull Instant occurredAt,
        @NotNull EventSeverity severity,
        @NotNull EventSource source,
        UUID sessionId,
        String username,
        Map<String, Object> data
) {
}
