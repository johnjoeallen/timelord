package com.timelord.controller.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.timelord.controller.agent.EventItem;
import com.timelord.controller.agent.EventSubmissionRequest;
import com.timelord.controller.agent.RegisterRequest;
import com.timelord.controller.agent.RegisterResponse;
import com.timelord.controller.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventQueryIntegrationTest extends AbstractIntegrationTest {

    private UUID registerDevice() {
        UUID deviceId = UUID.randomUUID();
        restTemplate.postForEntity("/api/v1/agents/register",
                new RegisterRequest(deviceId, "Event Test Device", "evt-host-" + deviceId, "0.1.0", "Windows 11",
                        "10.0", "x86_64", List.of("10.0.0.1")),
                RegisterResponse.class);
        return deviceId;
    }

    private void submit(UUID deviceId, EventType type, EventSeverity severity) {
        restTemplate.postForObject("/api/v1/agents/" + deviceId + "/events",
                new EventSubmissionRequest(List.of(new EventItem(UUID.randomUUID(), type, Instant.now(), severity,
                        EventSource.AGENT, null, null, Map.of()))),
                Map.class);
    }

    @Test
    void listingWithNoFiltersDoesNotFailOnPostgresNullParameterTypeInference() {
        UUID deviceId = registerDevice();
        submit(deviceId, EventType.AGENT_STARTED, EventSeverity.INFO);

        var response = restTemplate.getForEntity("/api/v1/events", Map.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void canFilterByEventTypeAndSeverity() {
        UUID deviceId = registerDevice();
        submit(deviceId, EventType.AGENT_STARTED, EventSeverity.INFO);
        submit(deviceId, EventType.AGENT_ERROR, EventSeverity.ERROR);

        Map<String, Object> filtered = restTemplate.getForObject(
                "/api/v1/events?eventType=AGENT_ERROR&severity=ERROR", Map.class);
        List<Map<String, Object>> items = (List<Map<String, Object>>) filtered.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("eventType")).isEqualTo("AGENT_ERROR");
    }

    @Test
    void canFilterByDeviceId() {
        UUID deviceA = registerDevice();
        UUID deviceB = registerDevice();
        submit(deviceA, EventType.AGENT_STARTED, EventSeverity.INFO);
        submit(deviceB, EventType.AGENT_STARTED, EventSeverity.INFO);

        Map<String, Object> filtered = restTemplate.getForObject("/api/v1/events?deviceId=" + deviceA, Map.class);
        List<Map<String, Object>> items = (List<Map<String, Object>>) filtered.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("deviceId")).isEqualTo(deviceA.toString());
    }

    @Test
    void eventPaginationReturnsCorrectPageMetadata() {
        UUID deviceId = registerDevice();
        for (int i = 0; i < 5; i++) {
            submit(deviceId, EventType.HEARTBEAT_SENT, EventSeverity.INFO);
        }

        // Scoped to this test's own device: other methods in this class share
        // the same database, so an unfiltered count would pick up their events too.
        Map<String, Object> page0 = restTemplate.getForObject(
                "/api/v1/events?deviceId=" + deviceId + "&page=0&size=2", Map.class);
        assertThat((List<?>) page0.get("items")).hasSize(2);
        assertThat(page0.get("totalElements")).isEqualTo(5);
        assertThat(page0.get("totalPages")).isEqualTo(3);
    }

    @Test
    void unknownEventIdReturns404() {
        var response = restTemplate.getForEntity("/api/v1/events/" + UUID.randomUUID(), Map.class);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
