package com.timelord.controller.agent;

import java.time.Instant;

public record HeartbeatResponse(
        boolean accepted,
        Instant serverTime,
        int heartbeatIntervalSeconds
) {
}
