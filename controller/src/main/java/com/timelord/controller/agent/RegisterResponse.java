package com.timelord.controller.agent;

import java.time.Instant;
import java.util.UUID;

public record RegisterResponse(
        UUID deviceId,
        UUID controllerId,
        Instant registeredAt,
        int heartbeatIntervalSeconds,
        boolean accepted
) {
}
