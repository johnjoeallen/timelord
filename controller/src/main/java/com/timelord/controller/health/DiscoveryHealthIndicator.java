package com.timelord.controller.health;

import com.timelord.controller.discovery.DiscoveryUdpListener;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("discovery")
public class DiscoveryHealthIndicator implements HealthIndicator {

    private final DiscoveryUdpListener listener;

    public DiscoveryHealthIndicator(DiscoveryUdpListener listener) {
        this.listener = listener;
    }

    @Override
    public Health health() {
        if (!listener.isEnabled()) {
            return Health.up().withDetail("enabled", false).build();
        }
        return listener.isHealthy()
                ? Health.up().withDetail("enabled", true).build()
                : Health.down().withDetail("enabled", true).withDetail("reason", "UDP socket not bound").build();
    }
}
