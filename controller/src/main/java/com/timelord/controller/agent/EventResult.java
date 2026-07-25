package com.timelord.controller.agent;

import java.util.UUID;

public record EventResult(
        UUID eventId,
        EventResultStatus status,
        String reason
) {
    public static EventResult accepted(UUID eventId) {
        return new EventResult(eventId, EventResultStatus.ACCEPTED, null);
    }

    public static EventResult duplicate(UUID eventId) {
        return new EventResult(eventId, EventResultStatus.DUPLICATE, null);
    }

    public static EventResult rejected(UUID eventId, String reason) {
        return new EventResult(eventId, EventResultStatus.REJECTED, reason);
    }
}
