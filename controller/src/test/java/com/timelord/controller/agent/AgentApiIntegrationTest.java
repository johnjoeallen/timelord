package com.timelord.controller.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.timelord.controller.common.ApiError;
import com.timelord.controller.device.DeviceDetail;
import com.timelord.controller.device.DeviceStatus;
import com.timelord.controller.device.NetworkInterfaceInfo;
import com.timelord.controller.event.EventSeverity;
import com.timelord.controller.event.EventSource;
import com.timelord.controller.event.EventType;
import com.timelord.controller.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class AgentApiIntegrationTest extends AbstractIntegrationTest {

    private RegisterRequest newRegisterRequest(UUID deviceId, String name) {
        return new RegisterRequest(deviceId, name, "test-host", "0.1.0", "Windows 11", "10.0.26100",
                "x86_64", List.of("192.168.1.50"),
                List.of(new NetworkInterfaceInfo("Ethernet", "AA:BB:CC:DD:EE:FF", List.of("192.168.1.50"))));
    }

    @Test
    void registeringNewDeviceSucceeds() {
        UUID deviceId = UUID.randomUUID();

        ResponseEntity<RegisterResponse> response = restTemplate.postForEntity(
                "/api/v1/agents/register", newRegisterRequest(deviceId, "Gaming PC"), RegisterResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().deviceId()).isEqualTo(deviceId);
        assertThat(response.getBody().accepted()).isTrue();
        assertThat(response.getBody().heartbeatIntervalSeconds()).isPositive();

        ResponseEntity<DeviceDetail> detail = restTemplate.getForEntity(
                "/api/v1/devices/" + deviceId, DeviceDetail.class);
        assertThat(detail.getBody().deviceName()).isEqualTo("Gaming PC");
        assertThat(detail.getBody().status()).isEqualTo(DeviceStatus.ONLINE);
    }

    @Test
    void reregisteringExistingDeviceUpdatesMetadataWithoutDuplicating() {
        UUID deviceId = UUID.randomUUID();
        restTemplate.postForEntity("/api/v1/agents/register", newRegisterRequest(deviceId, "Original Name"), RegisterResponse.class);

        RegisterResponse second = restTemplate.postForObject(
                "/api/v1/agents/register", newRegisterRequest(deviceId, "Renamed"), RegisterResponse.class);
        assertThat(second.deviceId()).isEqualTo(deviceId);

        DeviceDetail detail = restTemplate.getForObject("/api/v1/devices/" + deviceId, DeviceDetail.class);
        assertThat(detail.deviceName()).isEqualTo("Renamed");
    }

    @Test
    void registeringWithMissingRequiredFieldsReturns400() {
        RegisterRequest invalid = new RegisterRequest(null, "", "", null, null, null, null, null, null);

        ResponseEntity<ApiError> response = restTemplate.postForEntity(
                "/api/v1/agents/register", invalid, ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void heartbeatForUnknownDeviceReturns404() {
        HeartbeatRequest heartbeat = new HeartbeatRequest(UUID.randomUUID(), Instant.now(), "0.1.0", 100L,
                "RUNNING", "alice", "ACTIVE", 5L, null);

        ResponseEntity<ApiError> response = restTemplate.postForEntity(
                "/api/v1/agents/" + UUID.randomUUID() + "/heartbeat", heartbeat, ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("DEVICE_NOT_FOUND");
    }

    @Test
    void heartbeatUpdatesDeviceState() {
        UUID deviceId = UUID.randomUUID();
        restTemplate.postForEntity("/api/v1/agents/register", newRegisterRequest(deviceId, "HB Device"), RegisterResponse.class);

        HeartbeatRequest heartbeat = new HeartbeatRequest(UUID.randomUUID(), Instant.now(), "0.1.0", 3600L,
                "RUNNING", "alice", "ACTIVE", 12L, null);
        ResponseEntity<HeartbeatResponse> response = restTemplate.postForEntity(
                "/api/v1/agents/" + deviceId + "/heartbeat", heartbeat, HeartbeatResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().accepted()).isTrue();

        DeviceDetail detail = restTemplate.getForObject("/api/v1/devices/" + deviceId, DeviceDetail.class);
        assertThat(detail.currentUser()).isEqualTo("alice");
        assertThat(detail.sessionState()).isEqualTo("ACTIVE");
        assertThat(detail.idleSeconds()).isEqualTo(12L);
        assertThat(detail.status()).isEqualTo(DeviceStatus.ONLINE);
        assertThat(detail.active()).isTrue();
    }

    @Test
    void deviceIsNotActiveWithoutACurrentUser() {
        UUID deviceId = UUID.randomUUID();
        restTemplate.postForEntity("/api/v1/agents/register", newRegisterRequest(deviceId, "No User Device"), RegisterResponse.class);

        HeartbeatRequest heartbeat = new HeartbeatRequest(UUID.randomUUID(), Instant.now(), "0.1.0", 60L,
                "RUNNING", null, "NO_SESSION", null, null);
        restTemplate.postForEntity("/api/v1/agents/" + deviceId + "/heartbeat", heartbeat, HeartbeatResponse.class);

        DeviceDetail detail = restTemplate.getForObject("/api/v1/devices/" + deviceId, DeviceDetail.class);
        assertThat(detail.status()).isEqualTo(DeviceStatus.ONLINE);
        assertThat(detail.active()).isFalse();
    }

    @Test
    void submittingEventForUnknownDeviceReturns404() {
        EventSubmissionRequest request = new EventSubmissionRequest(List.of(
                new EventItem(UUID.randomUUID(), EventType.AGENT_STARTED, Instant.now(), EventSeverity.INFO,
                        EventSource.AGENT, null, null, Map.of())));

        ResponseEntity<ApiError> response = restTemplate.postForEntity(
                "/api/v1/agents/" + UUID.randomUUID() + "/events", request, ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void submittingSameEventTwiceIsReportedAsDuplicateNotStoredTwice() {
        UUID deviceId = UUID.randomUUID();
        restTemplate.postForEntity("/api/v1/agents/register", newRegisterRequest(deviceId, "Dup Device"), RegisterResponse.class);

        UUID eventId = UUID.randomUUID();
        EventSubmissionRequest request = new EventSubmissionRequest(List.of(
                new EventItem(eventId, EventType.AGENT_STARTED, Instant.now(), EventSeverity.INFO,
                        EventSource.AGENT, null, null, Map.of("startReason", "TEST"))));

        EventSubmissionResponse first = restTemplate.postForObject(
                "/api/v1/agents/" + deviceId + "/events", request, EventSubmissionResponse.class);
        assertThat(first.accepted()).isEqualTo(1);
        assertThat(first.results().get(0).status()).isEqualTo(EventResultStatus.ACCEPTED);

        EventSubmissionResponse second = restTemplate.postForObject(
                "/api/v1/agents/" + deviceId + "/events", request, EventSubmissionResponse.class);
        assertThat(second.accepted()).isEqualTo(0);
        assertThat(second.duplicates()).isEqualTo(1);
        assertThat(second.results().get(0).status()).isEqualTo(EventResultStatus.DUPLICATE);

        var events = restTemplate.getForObject("/api/v1/devices/" + deviceId + "/events", Map.class);
        List<?> items = (List<?>) events.get("items");
        assertThat(items).hasSize(1);
    }

    @Test
    void submittingBatchWithOneDuplicateReportsMixedResults() {
        UUID deviceId = UUID.randomUUID();
        restTemplate.postForEntity("/api/v1/agents/register", newRegisterRequest(deviceId, "Batch Device"), RegisterResponse.class);

        UUID firstEventId = UUID.randomUUID();
        restTemplate.postForObject("/api/v1/agents/" + deviceId + "/events",
                new EventSubmissionRequest(List.of(new EventItem(firstEventId, EventType.AGENT_STARTED, Instant.now(),
                        EventSeverity.INFO, EventSource.AGENT, null, null, Map.of()))),
                EventSubmissionResponse.class);

        UUID secondEventId = UUID.randomUUID();
        EventSubmissionRequest batch = new EventSubmissionRequest(List.of(
                new EventItem(firstEventId, EventType.AGENT_STARTED, Instant.now(), EventSeverity.INFO,
                        EventSource.AGENT, null, null, Map.of()),
                new EventItem(secondEventId, EventType.AGENT_STOPPING, Instant.now(), EventSeverity.INFO,
                        EventSource.AGENT, null, null, Map.of())));

        EventSubmissionResponse response = restTemplate.postForObject(
                "/api/v1/agents/" + deviceId + "/events", batch, EventSubmissionResponse.class);

        assertThat(response.accepted()).isEqualTo(1);
        assertThat(response.duplicates()).isEqualTo(1);
        assertThat(response.results()).hasSize(2);
    }
}
