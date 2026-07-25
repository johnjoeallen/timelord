package com.timelord.controller.discovery;

import java.util.UUID;

public record DiscoverRequestMessage(
        String protocol,
        int protocolVersion,
        String messageType,
        UUID requestId,
        String agentVersion,
        UUID deviceId,
        String hostname
) {
}
