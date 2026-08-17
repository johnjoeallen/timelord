package com.timelord.controller.device;

import java.net.InetAddress;
import java.net.UnknownHostException;
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
        List<NetworkInterfaceInfo> networkInterfaces,
        String sourceIp,
        DeviceStatus status,
        boolean active,
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
                withoutLinkLocal(device.getLocalIpAddresses()),
                device.getNetworkInterfaces(),
                device.getSourceIp(),
                device.getStatus(),
                device.isActive(),
                device.getServiceState(),
                device.getSessionState(),
                device.getCurrentUsername(),
                device.getIdleSeconds(),
                device.getRegisteredAt(),
                device.getLastRegistrationAt(),
                device.getLastHeartbeatAt()
        );
    }

    /**
     * Drops loopback/link-local addresses (169.254.0.0/16, fe80::/10) from
     * an agent-reported IP list — never reachable from another host, so
     * only ever noise. Filtered here rather than trusting the agent to
     * always have sent a clean list, since a device's stored
     * localIpAddresses is whatever it last registered with; an older agent
     * build's un-filtered list stays on the row until that device
     * re-registers with a newer build. Tolerates both the old bare
     * "10.0.0.1" format and the newer "Ethernet: 10.0.0.1" format.
     */
    private static List<String> withoutLinkLocal(List<String> addresses) {
        if (addresses == null) {
            return List.of();
        }
        return addresses.stream().filter(DeviceDetail::isRoutable).toList();
    }

    private static boolean isRoutable(String entry) {
        String ip = entry.contains(": ") ? entry.substring(entry.lastIndexOf(": ") + 2) : entry;
        try {
            InetAddress addr = InetAddress.getByName(ip);
            return !addr.isLoopbackAddress() && !addr.isLinkLocalAddress();
        } catch (UnknownHostException e) {
            return true; // not a parseable literal — keep it rather than silently dropping it
        }
    }
}
