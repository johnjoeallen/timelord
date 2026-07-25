package com.timelord.controller.discovery;

import java.util.UUID;

public record ControllerAvailableMessage(
        String protocol,
        int protocolVersion,
        String messageType,
        UUID requestId,
        UUID controllerId,
        String controllerName,
        String controllerUrl,
        int priority
) {
    static ControllerAvailableMessage forRequest(UUID requestId, UUID controllerId, String controllerName,
                                                  String controllerUrl, int priority) {
        return new ControllerAvailableMessage(DiscoveryConstants.PROTOCOL_NAME, DiscoveryConstants.PROTOCOL_VERSION,
                DiscoveryConstants.TYPE_CONTROLLER_AVAILABLE, requestId, controllerId, controllerName, controllerUrl, priority);
    }
}
