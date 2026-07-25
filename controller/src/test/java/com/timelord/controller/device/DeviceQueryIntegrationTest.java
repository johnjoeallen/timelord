package com.timelord.controller.device;

import static org.assertj.core.api.Assertions.assertThat;

import com.timelord.controller.agent.RegisterRequest;
import com.timelord.controller.agent.RegisterResponse;
import com.timelord.controller.support.AbstractIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DeviceQueryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private DeviceService deviceService;

    private UUID register(String name, String hostname) {
        UUID deviceId = UUID.randomUUID();
        restTemplate.postForEntity("/api/v1/agents/register",
                new RegisterRequest(deviceId, name, hostname, "0.1.0", "Windows 11", "10.0", "x86_64", List.of("10.0.0.1")),
                RegisterResponse.class);
        return deviceId;
    }

    @Test
    void listCanBeFilteredByHostname() {
        register("Alpha", "alpha-host");
        register("Beta", "beta-host");

        Map<String, Object> response = restTemplate.getForObject("/api/v1/devices?hostname=alpha", Map.class);
        List<?> items = (List<?>) response.get("items");
        assertThat(items).hasSize(1);
    }

    @Test
    void listSupportsPagination() {
        // Shared hostname prefix so the hostname filter scopes this
        // assertion to devices this test created — other methods in this
        // class register devices against the same database.
        String prefix = "page-test-" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            register("Device " + i, prefix + "-" + i);
        }

        Map<String, Object> page0 = restTemplate.getForObject("/api/v1/devices?hostname=" + prefix + "&page=0&size=2", Map.class);
        assertThat((List<?>) page0.get("items")).hasSize(2);
        assertThat(page0.get("totalPages")).isEqualTo(3);

        Map<String, Object> page2 = restTemplate.getForObject("/api/v1/devices?hostname=" + prefix + "&page=2&size=2", Map.class);
        assertThat((List<?>) page2.get("items")).hasSize(1);
    }

    @Test
    void deviceGoesOfflineOnceLastSignalIsOlderThanThreshold() {
        UUID deviceId = register("Aging Device", "aging-host");

        // Backdate the device's only signal of life (registration) well past
        // the offline threshold, then run the same recompute the scheduler
        // calls periodically.
        Device device = deviceRepository.findById(deviceId).orElseThrow();
        device.setLastRegistrationAt(Instant.now().minus(1, ChronoUnit.HOURS));
        deviceRepository.save(device);

        deviceService.recomputeOnlineStatus();

        Device reloaded = deviceRepository.findById(deviceId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(DeviceStatus.OFFLINE);
    }

    @Test
    void onlineFilterOnlyReturnsCurrentlyOnlineDevices() {
        UUID staleDeviceId = register("Stale Device", "stale-host-" + UUID.randomUUID());
        register("Fresh Device", "fresh-host-" + UUID.randomUUID());

        Device stale = deviceRepository.findById(staleDeviceId).orElseThrow();
        stale.setLastRegistrationAt(Instant.now().minus(1, ChronoUnit.HOURS));
        deviceRepository.save(stale);
        deviceService.recomputeOnlineStatus();

        Map<String, Object> onlineOnly = restTemplate.getForObject("/api/v1/devices?online=true", Map.class);
        List<Map<String, Object>> onlineItems = (List<Map<String, Object>>) onlineOnly.get("items");
        assertThat(onlineItems).extracting(item -> item.get("deviceName")).doesNotContain("Stale Device");

        Map<String, Object> offlineOnly = restTemplate.getForObject("/api/v1/devices?online=false", Map.class);
        List<Map<String, Object>> offlineItems = (List<Map<String, Object>>) offlineOnly.get("items");
        assertThat(offlineItems).extracting(item -> item.get("deviceName")).contains("Stale Device");
    }

    @Test
    void unknownDeviceDetailReturns404() {
        var response = restTemplate.getForEntity("/api/v1/devices/" + UUID.randomUUID(), Map.class);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
