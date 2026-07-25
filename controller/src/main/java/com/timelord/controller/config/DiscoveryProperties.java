package com.timelord.controller.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "timelord.discovery")
public record DiscoveryProperties(
        boolean enabled,
        int port,
        boolean announcementEnabled,
        Duration announcementInterval,
        int priority
) {
}
