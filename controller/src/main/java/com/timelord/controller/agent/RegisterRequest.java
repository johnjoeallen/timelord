package com.timelord.controller.agent;

import com.timelord.controller.device.NetworkInterfaceInfo;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record RegisterRequest(
        @NotNull UUID deviceId,
        @NotBlank String deviceName,
        @NotBlank String hostname,
        String agentVersion,
        String operatingSystem,
        String operatingSystemVersion,
        String architecture,
        List<String> localIpAddresses,
        List<NetworkInterfaceInfo> networkInterfaces
) {
}
