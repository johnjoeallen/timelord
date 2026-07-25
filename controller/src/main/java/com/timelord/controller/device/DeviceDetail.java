package com.timelord.controller.device;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DeviceDetail(
        UUID deviceId,
        String deviceName,
        String hostname,
        String agentVersion,
        String operatingSystem,
        String operatingSystemVersion,
        String architecture,
        List<String> localIpAddresses,
        String sourceIp,
        DeviceStatus status,
        String serviceState,
        String sessionState,
        String currentUser,
        Long idleSeconds,
        Instant registeredAt,
        Instant lastRegistrationAt,
        Instant lastHeartbeatAt
) {
    public static DeviceDetail from(Device device) {
        return new DeviceDetail(
                device.getId(),
                device.getDeviceName(),
                device.getHostname(),
                device.getAgentVersion(),
                device.getOperatingSystem(),
                device.getOperatingSystemVersion(),
                device.getArchitecture(),
                device.getLocalIpAddresses(),
                device.getSourceIp(),
                device.getStatus(),
                device.getServiceState(),
                device.getSessionState(),
                device.getCurrentUsername(),
                device.getIdleSeconds(),
                device.getRegisteredAt(),
                device.getLastRegistrationAt(),
                device.getLastHeartbeatAt()
        );
    }
}
