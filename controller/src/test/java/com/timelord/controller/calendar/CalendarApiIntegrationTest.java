package com.timelord.controller.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.timelord.controller.agent.AgentSchedule;
import com.timelord.controller.agent.HeartbeatRequest;
import com.timelord.controller.agent.HeartbeatResponse;
import com.timelord.controller.agent.RegisterRequest;
import com.timelord.controller.calendar.CalendarDto.Rule;
import com.timelord.controller.common.ApiError;
import com.timelord.controller.device.DeviceController;
import com.timelord.controller.device.DeviceController.CalendarAssignment;
import com.timelord.controller.device.DeviceDetail;
import com.timelord.controller.device.NetworkInterfaceInfo;
import com.timelord.controller.support.AbstractIntegrationTest;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class CalendarApiIntegrationTest extends AbstractIntegrationTest {

    private CalendarRequest request(String name) {
        return new CalendarRequest(name, "Europe/Dublin", CalendarAction.SLEEP, List.of(
                new Rule(List.of(DayOfWeek.values()), LocalTime.of(22, 30), LocalTime.of(6, 30))));
    }

    private UUID registerDevice() {
        UUID deviceId = UUID.randomUUID();
        restTemplate.postForEntity("/api/v1/agents/register", new RegisterRequest(deviceId, "Kid PC", "kid-pc", "0.1.0",
                "Windows 11", "10.0", "x86_64", List.of("192.168.1.60"),
                List.of(new NetworkInterfaceInfo("Ethernet", "AA:BB:CC:DD:EE:01", List.of("192.168.1.60")))), Object.class);
        return deviceId;
    }

    private HeartbeatResponse heartbeat(UUID deviceId) {
        return restTemplate.postForObject("/api/v1/agents/" + deviceId + "/heartbeat",
                new HeartbeatRequest(UUID.randomUUID(), Instant.now(), "0.1.0", 10L, "RUNNING", null, null, null, null),
                HeartbeatResponse.class);
    }

    @Test
    void deviceWithoutCalendarGetsNoSchedule() {
        assertThat(heartbeat(registerDevice()).schedule()).isNull();
    }

    @Test
    void assignedCalendarIsSentToAgentAndOverridesLeaveBaseUntouched() {
        CalendarDto calendar = restTemplate.postForObject("/api/v1/calendars", request("School nights"), CalendarDto.class);
        UUID deviceId = registerDevice();

        DeviceDetail assigned = restTemplate.exchange("/api/v1/devices/" + deviceId + "/calendar", HttpMethod.PUT,
                new HttpEntity<>(new CalendarAssignment(calendar.id())), DeviceDetail.class).getBody();
        assertThat(assigned.calendarId()).isEqualTo(calendar.id());

        HeartbeatResponse before = heartbeat(deviceId);
        assertThat(before.schedule().action()).isEqualTo(CalendarAction.SLEEP);
        assertThat(before.schedule().windows()).hasSizeGreaterThanOrEqualTo(7);

        LocalDate today = LocalDate.now();
        CalendarDto withOverride = restTemplate.postForObject("/api/v1/calendars/" + calendar.id() + "/overrides",
                new OverrideRequest("Holiday", today, today.plusDays(30), LocalTime.of(1, 0), LocalTime.of(6, 30)),
                CalendarDto.class);
        assertThat(withOverride.overrides()).hasSize(1);
        assertThat(withOverride.rules()).isEqualTo(calendar.rules());

        ResponseEntity<CalendarDto> removed = restTemplate.exchange(
                "/api/v1/calendars/" + calendar.id() + "/overrides/" + withOverride.overrides().get(0).id(),
                HttpMethod.DELETE, null, CalendarDto.class);
        assertThat(removed.getBody().overrides()).isEmpty();
        assertThat(removed.getBody().rules()).isEqualTo(calendar.rules());
    }

    @Test
    void invalidCalendarsAreRejected() {
        CalendarRequest badZone = new CalendarRequest("Bad zone " + UUID.randomUUID(), "Mars/Olympus", CalendarAction.SLEEP, List.of());
        assertThat(restTemplate.postForEntity("/api/v1/calendars", badZone, ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        CalendarRequest dup = new CalendarRequest("Dup days " + UUID.randomUUID(), "Europe/Dublin", CalendarAction.SLEEP, List.of(
                new Rule(List.of(DayOfWeek.MONDAY), LocalTime.of(22, 0), LocalTime.of(6, 0)),
                new Rule(List.of(DayOfWeek.MONDAY), LocalTime.of(23, 0), LocalTime.of(6, 0))));
        assertThat(restTemplate.postForEntity("/api/v1/calendars", dup, ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void duplicateNameIsConflict() {
        CalendarRequest r = request("Unique " + UUID.randomUUID());
        restTemplate.postForEntity("/api/v1/calendars", r, CalendarDto.class);
        assertThat(restTemplate.postForEntity("/api/v1/calendars", r, ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void deletingCalendarClearsDeviceAssignment() {
        CalendarDto calendar = restTemplate.postForObject("/api/v1/calendars", request("Temp " + UUID.randomUUID()), CalendarDto.class);
        UUID deviceId = registerDevice();
        restTemplate.exchange("/api/v1/devices/" + deviceId + "/calendar", HttpMethod.PUT,
                new HttpEntity<>(new CalendarAssignment(calendar.id())), DeviceDetail.class);

        restTemplate.delete("/api/v1/calendars/" + calendar.id());

        assertThat(restTemplate.getForObject("/api/v1/devices/" + deviceId, DeviceDetail.class).calendarId()).isNull();
        assertThat(heartbeat(deviceId).schedule()).isNull();
    }

    @Test
    void extendingDelaysNextWindowAndWakesLongPollingAgent() throws Exception {
        CalendarDto calendar = restTemplate.postForObject("/api/v1/calendars", request("Extend " + UUID.randomUUID()), CalendarDto.class);
        UUID deviceId = registerDevice();
        restTemplate.exchange("/api/v1/devices/" + deviceId + "/calendar", HttpMethod.PUT,
                new HttpEntity<>(new CalendarAssignment(calendar.id())), DeviceDetail.class);
        AgentSchedule before = heartbeat(deviceId).schedule();

        // Agent parks a long-poll holding its current revision...
        var poll = java.util.concurrent.CompletableFuture.supplyAsync(() -> restTemplate.getForObject(
                "/api/v1/agents/" + deviceId + "/schedule?revision=" + before.revision() + "&waitSeconds=20", AgentSchedule.class));
        Thread.sleep(500);
        assertThat(poll).isNotDone();

        // ...and the admin grants 30 more minutes.
        restTemplate.postForEntity("/api/v1/devices/" + deviceId + "/extend", new DeviceController.ExtendRequest(30), DeviceDetail.class);

        AgentSchedule after = poll.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(after.revision()).isNotEqualTo(before.revision());
        OffWindow first = after.windows().get(0);
        OffWindow originalFirst = before.windows().get(0);
        // Either the in-progress window now starts 30 min from now, or the next one starts 30 min later.
        assertThat(first.offAt()).isAfter(originalFirst.offAt());
        assertThat(first.onAt()).isEqualTo(originalFirst.onAt());
        // Base calendar is unchanged.
        assertThat(restTemplate.getForObject("/api/v1/calendars/" + calendar.id(), CalendarDto.class).rules())
                .isEqualTo(calendar.rules());
    }

    @Test
    void longPollWithCurrentRevisionTimesOutWithSameSchedule() {
        CalendarDto calendar = restTemplate.postForObject("/api/v1/calendars", request("Poll " + UUID.randomUUID()), CalendarDto.class);
        UUID deviceId = registerDevice();
        restTemplate.exchange("/api/v1/devices/" + deviceId + "/calendar", HttpMethod.PUT,
                new HttpEntity<>(new CalendarAssignment(calendar.id())), DeviceDetail.class);
        AgentSchedule current = heartbeat(deviceId).schedule();

        AgentSchedule polled = restTemplate.getForObject(
                "/api/v1/agents/" + deviceId + "/schedule?revision=" + current.revision() + "&waitSeconds=1", AgentSchedule.class);

        assertThat(polled.revision()).isEqualTo(current.revision());
    }

    @Test
    void extendWithoutCalendarIsConflict() {
        UUID deviceId = registerDevice();
        assertThat(restTemplate.postForEntity("/api/v1/devices/" + deviceId + "/extend",
                new DeviceController.ExtendRequest(30), ApiError.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
