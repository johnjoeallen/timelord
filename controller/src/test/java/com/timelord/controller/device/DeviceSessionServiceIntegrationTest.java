package com.timelord.controller.device;

import static org.assertj.core.api.Assertions.assertThat;

import com.timelord.controller.agent.EventItem;
import com.timelord.controller.agent.EventSubmissionRequest;
import com.timelord.controller.agent.EventSubmissionResponse;
import com.timelord.controller.agent.RegisterRequest;
import com.timelord.controller.agent.RegisterResponse;
import com.timelord.controller.event.EventSeverity;
import com.timelord.controller.event.EventSource;
import com.timelord.controller.event.EventType;
import com.timelord.controller.support.AbstractIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DeviceSessionServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private DeviceSessionService sessionService;

    /** Truncated to microseconds so constructed timestamps survive the JSON/Postgres round-trip exactly. */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private UUID register(String hostname) {
        UUID deviceId = UUID.randomUUID();
        restTemplate.postForEntity("/api/v1/agents/register",
                new RegisterRequest(deviceId, hostname, hostname, "0.1.0", "Windows 11", "10.0", "x86_64", List.of("10.0.0.1"), List.of()),
                RegisterResponse.class);
        return deviceId;
    }

    private void submit(UUID deviceId, EventType type, Instant occurredAt) {
        submit(deviceId, type, occurredAt, null);
    }

    private void submit(UUID deviceId, EventType type, Instant occurredAt, String username) {
        EventSubmissionRequest request = new EventSubmissionRequest(List.of(
                new EventItem(UUID.randomUUID(), type, occurredAt, EventSeverity.INFO, EventSource.AGENT, null, username, Map.of())));
        EventSubmissionResponse response = restTemplate.postForObject(
                "/api/v1/agents/" + deviceId + "/events", request, EventSubmissionResponse.class);
        assertThat(response.accepted()).isEqualTo(1);
    }

    private void setStatus(UUID deviceId, DeviceStatus status, Instant lastHeartbeatAt) {
        Device device = deviceRepository.findById(deviceId).orElseThrow();
        device.setStatus(status);
        device.setLastHeartbeatAt(lastHeartbeatAt);
        deviceRepository.save(device);
    }

    @Test
    void agentStartedAloneWithNoLoginProducesNoSession() {
        UUID deviceId = register("no-login-host-" + UUID.randomUUID());
        Instant start = now().minus(10, ChronoUnit.MINUTES);
        submit(deviceId, EventType.AGENT_STARTED, start);
        submit(deviceId, EventType.HEARTBEAT_SENT, now().minus(30, ChronoUnit.SECONDS));
        setStatus(deviceId, DeviceStatus.ONLINE, now().minus(30, ChronoUnit.SECONDS));

        Device device = deviceRepository.findById(deviceId).orElseThrow();
        List<DeviceSession> sessions = sessionService.sessionsFor(device);

        assertThat(sessions).isEmpty();
    }

    @Test
    void logonThenLogoffClosesOneSessionAsLoggedOut() {
        UUID deviceId = register("logout-host-" + UUID.randomUUID());
        Instant start = now().minus(1, ChronoUnit.HOURS);
        submit(deviceId, EventType.USER_LOGON, start, "alice");
        submit(deviceId, EventType.USER_LOGOFF, start.plus(15, ChronoUnit.MINUTES));

        Device device = deviceRepository.findById(deviceId).orElseThrow();
        List<DeviceSession> sessions = sessionService.sessionsFor(device);

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).isOngoing()).isFalse();
        assertThat(sessions.get(0).endReason()).isEqualTo(DeviceSession.EndReason.LOGGED_OUT);
        assertThat(sessions.get(0).durationSeconds()).isEqualTo(900);
        assertThat(sessions.get(0).username()).isEqualTo("alice");
    }

    @Test
    void logonThenStoppingClosesOneSessionAsStopped() {
        UUID deviceId = register("stopped-host-" + UUID.randomUUID());
        Instant start = now().minus(1, ChronoUnit.HOURS);
        submit(deviceId, EventType.USER_LOGON, start);
        submit(deviceId, EventType.AGENT_STOPPING, start.plus(30, ChronoUnit.MINUTES));

        Device device = deviceRepository.findById(deviceId).orElseThrow();
        List<DeviceSession> sessions = sessionService.sessionsFor(device);

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).isOngoing()).isFalse();
        assertThat(sessions.get(0).endReason()).isEqualTo(DeviceSession.EndReason.STOPPED);
        assertThat(sessions.get(0).durationSeconds()).isEqualTo(1800);
    }

    @Test
    void suspendClosesTheSessionAsSuspended() {
        UUID deviceId = register("suspend-host-" + UUID.randomUUID());
        Instant start = now().minus(1, ChronoUnit.HOURS);
        submit(deviceId, EventType.USER_LOGON, start);
        submit(deviceId, EventType.SYSTEM_SUSPEND, start.plus(20, ChronoUnit.MINUTES));

        Device device = deviceRepository.findById(deviceId).orElseThrow();
        List<DeviceSession> sessions = sessionService.sessionsFor(device);

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).endReason()).isEqualTo(DeviceSession.EndReason.SUSPENDED);
    }

    @Test
    void loggingBackInAfterSuspendStartsANewSession() {
        UUID deviceId = register("resume-host-" + UUID.randomUUID());
        // The still-open trailing session only counts as "ongoing" if its
        // last signal is within the session gap threshold of *now* — so
        // unlike the other tests, the tail end of this timeline has to be
        // genuinely recent, not just relatively spaced from `start`.
        Instant start = now().minus(90, ChronoUnit.MINUTES);
        submit(deviceId, EventType.USER_LOGON, start, "alice");
        submit(deviceId, EventType.SYSTEM_SUSPEND, start.plus(20, ChronoUnit.MINUTES));
        Instant loggedBackInAt = now().minus(2, ChronoUnit.MINUTES);
        submit(deviceId, EventType.USER_LOGON, loggedBackInAt, "bob");
        Instant lastHeartbeat = now().minus(30, ChronoUnit.SECONDS);
        submit(deviceId, EventType.HEARTBEAT_SENT, lastHeartbeat);
        setStatus(deviceId, DeviceStatus.ONLINE, lastHeartbeat);

        Device device = deviceRepository.findById(deviceId).orElseThrow();
        List<DeviceSession> sessions = sessionService.sessionsFor(device);

        assertThat(sessions).hasSize(2);
        // Newest first.
        assertThat(sessions.get(0).start()).isEqualTo(loggedBackInAt);
        assertThat(sessions.get(0).isOngoing()).isTrue();
        assertThat(sessions.get(0).username()).isEqualTo("bob");
        assertThat(sessions.get(1).endReason()).isEqualTo(DeviceSession.EndReason.SUSPENDED);
        assertThat(sessions.get(1).username()).isEqualTo("alice");
    }

    @Test
    void gapOverThreeMinutesSinceLastHeartbeatEndsTheOpenSessionAsDisappeared() {
        UUID deviceId = register("gap-host-" + UUID.randomUUID());
        Instant start = now().minus(1, ChronoUnit.HOURS);
        submit(deviceId, EventType.USER_LOGON, start);
        Instant lastBeforeGap = start.plus(1, ChronoUnit.MINUTES);
        submit(deviceId, EventType.HEARTBEAT_SENT, lastBeforeGap);
        Instant afterGap = lastBeforeGap.plus(10, ChronoUnit.MINUTES);
        // A heartbeat after the gap never restarts a session on its own —
        // only a fresh USER_LOGON does — so this is dropped as a no-op
        // once the prior session is closed for going silent too long.
        submit(deviceId, EventType.HEARTBEAT_SENT, afterGap);
        setStatus(deviceId, DeviceStatus.OFFLINE, afterGap);

        Device device = deviceRepository.findById(deviceId).orElseThrow();
        List<DeviceSession> sessions = sessionService.sessionsFor(device);

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).start()).isEqualTo(start);
        assertThat(sessions.get(0).end()).isEqualTo(lastBeforeGap);
        assertThat(sessions.get(0).endReason()).isEqualTo(DeviceSession.EndReason.DISAPPEARED);
    }

    @Test
    void gapUnderThreeMinutesStaysOneSession() {
        UUID deviceId = register("no-gap-host-" + UUID.randomUUID());
        Instant start = now().minus(1, ChronoUnit.HOURS);
        submit(deviceId, EventType.USER_LOGON, start);
        submit(deviceId, EventType.HEARTBEAT_SENT, start.plus(2, ChronoUnit.MINUTES));
        Instant lastHeartbeat = start.plus(4, ChronoUnit.MINUTES);
        submit(deviceId, EventType.HEARTBEAT_SENT, lastHeartbeat);
        submit(deviceId, EventType.AGENT_STOPPING, start.plus(5, ChronoUnit.MINUTES));

        Device device = deviceRepository.findById(deviceId).orElseThrow();
        List<DeviceSession> sessions = sessionService.sessionsFor(device);

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).endReason()).isEqualTo(DeviceSession.EndReason.STOPPED);
    }
}
