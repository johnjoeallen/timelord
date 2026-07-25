package com.timelord.controller.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "timelord.agent-defaults")
public record AgentDefaultsProperties(
        int heartbeatIntervalSeconds
) {
}
