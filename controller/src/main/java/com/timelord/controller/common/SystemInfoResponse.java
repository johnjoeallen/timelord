package com.timelord.controller.common;

import java.time.Instant;
import java.util.UUID;

public record SystemInfoResponse(
        UUID controllerId,
        String controllerName,
        int protocolVersion,
        String controllerVersion,
        Instant serverTime
) {
}
