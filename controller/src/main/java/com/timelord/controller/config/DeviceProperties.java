package com.timelord.controller.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "timelord.device")
public record DeviceProperties(
        int offlineAfterMissedHeartbeats,
        long offlineMinimumSeconds
) {
}
