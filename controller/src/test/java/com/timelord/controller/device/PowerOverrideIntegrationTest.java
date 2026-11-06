package com.timelord.controller.device;

import static org.assertj.core.api.Assertions.assertThat;

import com.timelord.controller.agent.AgentSchedule;
import com.timelord.controller.agent.HeartbeatRequest;
import com.timelord.controller.agent.HeartbeatResponse;
import com.timelord.controller.agent.RegisterRequest;
import com.timelord.controller.common.ApiError;
import com.timelord.controller.device.DeviceController.PowerRequest;
import com.timelord.controller.device.DeviceController.ScheduleAssignment;
import com.timelord.controller.device.DeviceController.Then;
import com.timelord.controller.schedule.OffWindow;
import com.timelord.controller.schedule.ScheduleAction;
import com.timelord.controller.schedule.ScheduleDto;
import com.timelord.controller.schedule.ScheduleDto.Rule;
import com.timelord.controller.schedule.ScheduleRequest;
import com.timelord.controller.support.AbstractIntegrationTest;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class PowerOverrideIntegrationTest extends AbstractIntegrationTest {

    private static final Instant FOREVER = Instant.parse("9999-12-31T00:00:00Z");

    private UUID registerDevice() {
        UUID deviceId = UUID.randomUUID();
        restTemplate.postForEntity("/api/v1/agents/register", new RegisterRequest(deviceId, "Kid PC", "kid-pc", "0.1.0",
                "Windows 11", "10.0", "x86_64", List.of("192.168.1.60"),
                List.of(new NetworkInterfaceInfo("Ethernet", "AA:BB:CC:DD:EE:01", List.of("192.168.1.60")))), Object.class);
        return deviceId;
    }

    private AgentSchedule heartbeat(UUID deviceId) {
        return restTemplate.postForObject("/api/v1/agents/" + deviceId + "/heartbeat",
                new HeartbeatRequest(UUID.randomUUID(), Instant.now(), "0.1.0", 10L, "RUNNING", null, null, null, null),
                HeartbeatResponse.class).schedule();
    }

    private void assignScheduleWithNightlyOffWindow(UUID deviceId, ScheduleAction action) {
        ScheduleDto schedule = restTemplate.postForObject("/api/v1/schedules", new ScheduleRequest(
                "Power " + UUID.randomUUID(), "Europe/Dublin", action, List.of(
                new Rule(List.of(DayOfWeek.values()), LocalTime.of(6, 30), LocalTime.of(22, 30)))), ScheduleDto.class);
        restTemplate.exchange("/api/v1/devices/" + deviceId + "/schedule", HttpMethod.PUT,
                new HttpEntity<>(new ScheduleAssignment(schedule.id())), DeviceDetail.class);
    }

    private ResponseEntity<DeviceDetail> power(UUID deviceId, PowerRequest request) {
        return restTemplate.exchange("/api/v1/devices/" + deviceId + "/power", HttpMethod.PUT,
                new HttpEntity<>(request), DeviceDetail.class);
    }

    @Test
    void offIsAnEndlessOffWindowEvenWithoutASchedule_andOnCancelsIt() {
        UUID deviceId = registerDevice();
        assertThat(heartbeat(deviceId)).isNull();

        power(deviceId, new PowerRequest(PowerMode.OFF, null, null, null));
        AgentSchedule off = heartbeat(deviceId);
        assertThat(off.action()).isEqualTo(ScheduleAction.SLEEP);
        assertThat(off.windows()).hasSize(1);
        assertThat(off.windows().get(0).offAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(off.windows().get(0).onAt()).isEqualTo(FOREVER);
        // Stable between heartbeats, so the agent's long poll doesn't churn.
        assertThat(heartbeat(deviceId).revision()).isEqualTo(off.revision());

        power(deviceId, new PowerRequest(PowerMode.ON, null, null, null));
        assertThat(heartbeat(deviceId)).isNull();
    }

    @Test
    void offUsesTheSchedulesActionAndBeatsAnExtension() {
        UUID deviceId = registerDevice();
        assignScheduleWithNightlyOffWindow(deviceId, ScheduleAction.SHUTDOWN);
        restTemplate.postForEntity("/api/v1/devices/" + deviceId + "/extend", new DeviceController.ExtendRequest(30), DeviceDetail.class);

        DeviceDetail detail = power(deviceId, new PowerRequest(PowerMode.OFF, null, null, null)).getBody();

        assertThat(detail.power().state()).isEqualTo(PowerStatus.State.OFF);
        AgentSchedule off = heartbeat(deviceId);
        assertThat(off.action()).isEqualTo(ScheduleAction.SHUTDOWN);
        assertThat(off.windows()).containsExactly(new OffWindow(off.windows().get(0).offAt(), FOREVER));
        // Nothing to extend while forced off.
        ResponseEntity<ApiError> extend = restTemplate.postForEntity("/api/v1/devices/" + deviceId + "/extend",
                new DeviceController.ExtendRequest(30), ApiError.class);
        assertThat(extend.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void offWithAGracePeriodStartsLater() {
        UUID deviceId = registerDevice();
        DeviceDetail detail = power(deviceId, new PowerRequest(PowerMode.OFF, null, null, 15)).getBody();

        assertThat(detail.power().state()).isEqualTo(PowerStatus.State.OFF_PENDING);
        Instant offAt = heartbeat(deviceId).windows().get(0).offAt();
        assertThat(offAt).isBetween(Instant.now().plus(Duration.ofMinutes(14)), Instant.now().plus(Duration.ofMinutes(16)));
    }

    @Test
    void anOnPeriodThenOffIgnoresTheScheduleAndTurnsOffAtTheEnd() {
        UUID deviceId = registerDevice();
        assignScheduleWithNightlyOffWindow(deviceId, ScheduleAction.SLEEP);

        DeviceDetail detail = power(deviceId, new PowerRequest(PowerMode.ON, 60, Then.OFF, null)).getBody();

        assertThat(detail.power().state()).isEqualTo(PowerStatus.State.HOLD);
        assertThat(detail.power().thenOff()).isTrue();
        List<OffWindow> windows = heartbeat(deviceId).windows();
        assertThat(windows).hasSize(1);
        assertThat(windows.get(0).offAt()).isBetween(Instant.now().plus(Duration.ofMinutes(59)), Instant.now().plus(Duration.ofMinutes(61)));
        assertThat(windows.get(0).onAt()).isEqualTo(FOREVER);
    }

    @Test
    void anOnPeriodThenScheduleOnlyHoldsOffTheNextOffWindow() {
        UUID deviceId = registerDevice();
        assignScheduleWithNightlyOffWindow(deviceId, ScheduleAction.SLEEP);
        OffWindow normalFirst = heartbeat(deviceId).windows().get(0);

        power(deviceId, new PowerRequest(PowerMode.ON, 24 * 60, Then.SCHEDULE, null));

        List<OffWindow> held = heartbeat(deviceId).windows();
        Instant holdEnd = Instant.now().plus(Duration.ofHours(24));
        assertThat(held).allSatisfy(w -> assertThat(w.onAt()).isAfter(holdEnd.minus(Duration.ofMinutes(1))));
        assertThat(held.get(0).offAt()).isAfterOrEqualTo(holdEnd.minus(Duration.ofMinutes(1)));
        assertThat(held).isNotEmpty();
        assertThat(held.get(0)).isNotEqualTo(normalFirst);

        // Back to following the schedule drops the hold.
        power(deviceId, new PowerRequest(PowerMode.ON, null, null, null));
        assertThat(heartbeat(deviceId).windows().get(0)).isEqualTo(normalFirst);
    }

    @Test
    void theDevicePageListsTheWindowsTheAgentIsTold() {
        UUID deviceId = registerDevice();
        assignScheduleWithNightlyOffWindow(deviceId, ScheduleAction.SLEEP);

        power(deviceId, new PowerRequest(PowerMode.OFF, null, null, 0));
        String forcedOff = restTemplate.getForObject("/devices/" + deviceId, String.class);
        assertThat(forcedOff).contains("until switched back to Scheduled").contains("Forced off since");

        // A day-long hold drops the schedule's windows that end before it does, so the page must not list them.
        power(deviceId, new PowerRequest(PowerMode.ON, 24 * 60, Then.SCHEDULE, null));
        String held = restTemplate.getForObject("/devices/" + deviceId, String.class);
        assertThat(held).doesNotContain("until switched back").contains("back to the schedule");
    }

    @Test
    void invalidRequestsAreRejected() {
        UUID deviceId = registerDevice();
        for (PowerRequest bad : List.of(
                new PowerRequest(PowerMode.OFF, null, null, 241),
                new PowerRequest(PowerMode.OFF, null, null, -1),
                new PowerRequest(PowerMode.OFF, 30, null, null),
                new PowerRequest(PowerMode.ON, null, null, 15),
                new PowerRequest(PowerMode.ON, null, Then.OFF, null),
                new PowerRequest(PowerMode.ON, 0, null, null),
                new PowerRequest(PowerMode.ON, 7 * 24 * 60 + 1, null, null),
                new PowerRequest(null, null, null, null))) {
            assertThat(restTemplate.exchange("/api/v1/devices/" + deviceId + "/power", HttpMethod.PUT,
                    new HttpEntity<>(bad), ApiError.class).getStatusCode()).as(bad.toString()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}
