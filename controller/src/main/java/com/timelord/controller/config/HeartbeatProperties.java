package com.timelord.controller.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "timelord.heartbeat")
public record HeartbeatProperties(
        int historyRetentionDays
) {
}
