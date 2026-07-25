package com.timelord.controller.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "timelord.controller")
public record ControllerProperties(
        String id,
        String name,
        String publicUrl,
        String version
) {
}
