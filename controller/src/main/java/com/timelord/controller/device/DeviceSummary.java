package com.timelord.controller.device;

import java.time.Instant;
import java.util.UUID;

public record DeviceSummary(
        UUID deviceId,
        String deviceName,
        String hostname,
        String agentVersion,
        String operatingSystem,
        DeviceStatus status,
        boolean active,
        String currentUser,
        String sessionState,
        String displayStatus,
        Long idleSeconds,
        Instant lastHeartbeatAt,
        String sourceIp
) {
    public static DeviceSummary from(Device device) {
        return new DeviceSummary(
                device.getId(),
                device.getDeviceName(),
                device.getHostname(),
                device.getAgentVersion(),
                device.getOperatingSystem(),
                device.getStatus(),
                device.isActive(),
                device.getCurrentUsername(),
                device.getSessionState(),
                device.getDisplayStatus(),
                device.getIdleSeconds(),
                device.getLastHeartbeatAt(),
                device.getSourceIp()
        );
    }
}
