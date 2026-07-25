package com.timelord.controller.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record EventDto(
        UUID eventId,
        UUID deviceId,
        String deviceName,
        EventType eventType,
        Instant occurredAt,
        Instant receivedAt,
        EventSeverity severity,
        EventSource source,
        UUID sessionId,
        String username,
        Map<String, Object> data
) {
    public static EventDto from(DeviceEvent event, String deviceName) {
        return new EventDto(
                event.getId(),
                event.getDeviceId(),
                deviceName,
                event.getEventType(),
                event.getOccurredAt(),
                event.getReceivedAt(),
                event.getSeverity(),
                event.getSource(),
                event.getSessionId(),
                event.getUsername(),
                event.getData()
        );
    }
}
