package com.timelord.controller.agent;

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
        List<String> localIpAddresses
) {
}
