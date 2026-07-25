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
        String currentUser,
        String sessionState,
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
                device.getCurrentUsername(),
                device.getSessionState(),
                device.getIdleSeconds(),
                device.getLastHeartbeatAt(),
                device.getSourceIp()
        );
    }
}
