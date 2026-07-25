package com.timelord.controller.discovery;

import java.time.Instant;
import java.util.UUID;

public record ControllerAnnouncementMessage(
        String protocol,
        int protocolVersion,
        String messageType,
        UUID controllerId,
        String controllerName,
        String controllerUrl,
        int priority,
        Instant sentAt
) {
    static ControllerAnnouncementMessage now(UUID controllerId, String controllerName, String controllerUrl, int priority) {
        return new ControllerAnnouncementMessage(DiscoveryConstants.PROTOCOL_NAME, DiscoveryConstants.PROTOCOL_VERSION,
                DiscoveryConstants.TYPE_CONTROLLER_ANNOUNCEMENT, controllerId, controllerName, controllerUrl, priority,
                Instant.now());
    }
}
