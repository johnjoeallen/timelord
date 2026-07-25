package com.timelord.controller.agent;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public record HeartbeatRequest(
        @NotNull UUID eventId,
        @NotNull Instant occurredAt,
        String agentVersion,
        Long uptimeSeconds,
        String serviceState,
        String currentUser,
        String sessionState,
        Long idleSeconds,
        String controllerUrl
) {
}
