package com.timelord.controller.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.timelord.controller.agent.AgentSchedule;
import com.timelord.controller.agent.HeartbeatRequest;
import com.timelord.controller.agent.HeartbeatResponse;
import com.timelord.controller.agent.RegisterRequest;
import com.timelord.controller.schedule.ScheduleDto.Rule;
import com.timelord.controller.common.ApiError;
import com.timelord.controller.device.DeviceController;
import com.timelord.controller.device.DeviceController.ScheduleAssignment;
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

class ScheduleApiIntegrationTest extends AbstractIntegrationTest {

    /** Allowed 06:30 to 22:30 every day, so there is an off window every night. */
    private ScheduleRequest request(String name) {
        return new ScheduleRequest(name, "Europe/Dublin", ScheduleAction.SLEEP, List.of(
                new Rule(List.of(DayOfWeek.values()), LocalTime.of(6, 30), LocalTime.of(22, 30))));
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

    private void assign(UUID deviceId, UUID scheduleId) {
        restTemplate.exchange("/api/v1/devices/" + deviceId + "/schedule", HttpMethod.PUT,
                new HttpEntity<>(new ScheduleAssignment(scheduleId)), DeviceDetail.class);
    }

    @Test
    void anOverrideCanBeDisabledThroughTheApi() {
        ScheduleDto created = restTemplate.postForObject("/api/v1/schedules", request("Toggle " + UUID.randomUUID()), ScheduleDto.class);
        ScheduleDto withOverride = restTemplate.postForObject("/api/v1/schedules/" + created.id() + "/overrides",
                new OverrideRequest("Holiday", java.time.LocalDate.parse("2030-02-01"), java.time.LocalDate.parse("2030-02-03"),
                        LocalTime.of(8, 0), LocalTime.of(23, 0)), ScheduleDto.class);
        UUID overrideId = withOverride.overrides().get(0).id();
        assertThat(withOverride.overrides().get(0).enabled()).isTrue();

        ScheduleDto off = restTemplate.exchange("/api/v1/schedules/" + created.id() + "/overrides/" + overrideId + "/enabled",
                HttpMethod.PUT, new HttpEntity<>(new EnabledRequest(false)), ScheduleDto.class).getBody();

        assertThat(off.overrides().get(0).enabled()).isFalse();
        assertThat(restTemplate.exchange("/api/v1/schedules/" + created.id() + "/overrides/" + UUID.randomUUID() + "/enabled",
                HttpMethod.PUT, new HttpEntity<>(new EnabledRequest(true)), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deviceWithoutScheduleGetsNoSchedule() {
        assertThat(heartbeat(registerDevice()).schedule()).isNull();
    }

    @Test
    void assignedScheduleIsSentToAgentAndOverridesLeaveBaseUntouched() {
        ScheduleDto schedule = restTemplate.postForObject("/api/v1/schedules", request("School nights"), ScheduleDto.class);
        UUID deviceId = registerDevice();

        DeviceDetail assigned = restTemplate.exchange("/api/v1/devices/" + deviceId + "/schedule", HttpMethod.PUT,
                new HttpEntity<>(new ScheduleAssignment(schedule.id())), DeviceDetail.class).getBody();
        assertThat(assigned.scheduleId()).isEqualTo(schedule.id());

        HeartbeatResponse before = heartbeat(deviceId);
        assertThat(before.schedule().action()).isEqualTo(ScheduleAction.SLEEP);
        assertThat(before.schedule().windows()).hasSizeGreaterThanOrEqualTo(7);

        LocalDate today = LocalDate.now();
        ScheduleDto withOverride = restTemplate.postForObject("/api/v1/schedules/" + schedule.id() + "/overrides",
                new OverrideRequest("Holiday", today, today.plusDays(30), LocalTime.of(6, 30), LocalTime.MIDNIGHT),
                ScheduleDto.class);
        assertThat(withOverride.overrides()).hasSize(1);
        assertThat(withOverride.rules()).isEqualTo(schedule.rules());

        ResponseEntity<ScheduleDto> removed = restTemplate.exchange(
                "/api/v1/schedules/" + schedule.id() + "/overrides/" + withOverride.overrides().get(0).id(),
                HttpMethod.DELETE, null, ScheduleDto.class);
        assertThat(removed.getBody().overrides()).isEmpty();
        assertThat(removed.getBody().rules()).isEqualTo(schedule.rules());
    }

    @Test
    void invalidSchedulesAreRejected() {
        ScheduleRequest badZone = new ScheduleRequest("Bad zone " + UUID.randomUUID(), "Mars/Olympus", ScheduleAction.SLEEP, List.of());
        assertThat(restTemplate.postForEntity("/api/v1/schedules", badZone, ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        ScheduleRequest noDays = new ScheduleRequest("No days " + UUID.randomUUID(), "Europe/Dublin", ScheduleAction.SLEEP, List.of(
                new Rule(List.of(), LocalTime.of(8, 0), LocalTime.of(9, 0))));
        assertThat(restTemplate.postForEntity("/api/v1/schedules", noDays, ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // A window can't run past midnight: 18:30 -> 01:00 is rejected, 18:30 -> 00:00 is fine.
        ScheduleRequest pastMidnight = new ScheduleRequest("Past midnight " + UUID.randomUUID(), "Europe/Dublin", ScheduleAction.SLEEP,
                List.of(new Rule(List.of(DayOfWeek.FRIDAY), LocalTime.of(18, 30), LocalTime.of(1, 0))));
        assertThat(restTemplate.postForEntity("/api/v1/schedules", pastMidnight, ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        ScheduleRequest toMidnight = new ScheduleRequest("To midnight " + UUID.randomUUID(), "Europe/Dublin", ScheduleAction.SLEEP,
                List.of(new Rule(List.of(DayOfWeek.FRIDAY), LocalTime.of(18, 30), LocalTime.MIDNIGHT)));
        assertThat(restTemplate.postForEntity("/api/v1/schedules", toMidnight, ScheduleDto.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ScheduleDto schedule = restTemplate.postForObject("/api/v1/schedules", request("Bad override " + UUID.randomUUID()), ScheduleDto.class);
        assertThat(restTemplate.postForEntity("/api/v1/schedules/" + schedule.id() + "/overrides",
                new OverrideRequest(null, LocalDate.now(), LocalDate.now(), LocalTime.of(18, 30), LocalTime.of(1, 0)), ApiError.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        LocalDate day = LocalDate.now();
        assertThat(restTemplate.postForEntity("/api/v1/schedules/" + schedule.id() + "/overrides",
                new OverrideRequest(null, day, day.minusDays(1), LocalTime.of(8, 0), LocalTime.of(9, 0)), ApiError.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void duplicateNameIsConflict() {
        ScheduleRequest r = request("Unique " + UUID.randomUUID());
        restTemplate.postForEntity("/api/v1/schedules", r, ScheduleDto.class);
        assertThat(restTemplate.postForEntity("/api/v1/schedules", r, ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void deletingScheduleClearsDeviceAssignment() {
        ScheduleDto schedule = restTemplate.postForObject("/api/v1/schedules", request("Temp " + UUID.randomUUID()), ScheduleDto.class);
        UUID deviceId = registerDevice();
        assign(deviceId, schedule.id());

        restTemplate.delete("/api/v1/schedules/" + schedule.id());

        assertThat(restTemplate.getForObject("/api/v1/devices/" + deviceId, DeviceDetail.class).scheduleId()).isNull();
        assertThat(heartbeat(deviceId).schedule()).isNull();
    }

    @Test
    void extendingDelaysNextWindowAndWakesLongPollingAgent() throws Exception {
        ScheduleDto schedule = restTemplate.postForObject("/api/v1/schedules", request("Extend " + UUID.randomUUID()), ScheduleDto.class);
        UUID deviceId = registerDevice();
        assign(deviceId, schedule.id());
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
        // Either the in-progress off window now starts 30 min from now, or the next one starts 30 min later.
        assertThat(first.offAt()).isAfter(originalFirst.offAt());
        assertThat(first.onAt()).isEqualTo(originalFirst.onAt());
        // Base schedule is unchanged.
        assertThat(restTemplate.getForObject("/api/v1/schedules/" + schedule.id(), ScheduleDto.class).rules())
                .isEqualTo(schedule.rules());
    }

    @Test
    void longPollWithCurrentRevisionTimesOutWithSameSchedule() {
        ScheduleDto schedule = restTemplate.postForObject("/api/v1/schedules", request("Poll " + UUID.randomUUID()), ScheduleDto.class);
        UUID deviceId = registerDevice();
        assign(deviceId, schedule.id());
        AgentSchedule current = heartbeat(deviceId).schedule();

        AgentSchedule polled = restTemplate.getForObject(
                "/api/v1/agents/" + deviceId + "/schedule?revision=" + current.revision() + "&waitSeconds=1", AgentSchedule.class);

        assertThat(polled.revision()).isEqualTo(current.revision());
    }

    @Test
    void extendWithoutScheduleIsConflict() {
        UUID deviceId = registerDevice();
        assertThat(restTemplate.postForEntity("/api/v1/devices/" + deviceId + "/extend",
                new DeviceController.ExtendRequest(30), ApiError.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ---- allowed windows: several rows on a day, and overrides that only extend

    /** 2030-01-07 is a Monday; Europe/Dublin is UTC+0 in January, so local times equal the instants. */
    private static final String MONDAY = "from=2030-01-07T00:00:00Z&days=1";

    private ScheduleDto twoWindowsOnMonday(String name) {
        return restTemplate.postForObject("/api/v1/schedules", new ScheduleRequest(name, "Europe/Dublin", ScheduleAction.SLEEP, List.of(
                new Rule(List.of(DayOfWeek.MONDAY), LocalTime.of(8, 30), LocalTime.of(10, 30)),
                new Rule(List.of(DayOfWeek.MONDAY), LocalTime.of(18, 30), LocalTime.of(23, 30)))), ScheduleDto.class);
    }

    @Test
    void severalRowsCoveringTheSameDayAreAcceptedAndBothApply() {
        ScheduleDto schedule = twoWindowsOnMonday("Two windows " + UUID.randomUUID());
        assertThat(schedule.rules()).hasSize(2);

        AllowedWindow[] allowed = restTemplate.getForObject("/api/v1/schedules/" + schedule.id() + "/allowed?" + MONDAY, AllowedWindow[].class);

        assertThat(allowed).containsExactly(
                new AllowedWindow(Instant.parse("2030-01-07T08:30:00Z"), Instant.parse("2030-01-07T10:30:00Z")),
                new AllowedWindow(Instant.parse("2030-01-07T18:30:00Z"), Instant.parse("2030-01-07T23:30:00Z")));
        OffWindow[] off = restTemplate.getForObject("/api/v1/schedules/" + schedule.id() + "/windows?" + MONDAY, OffWindow[].class);
        assertThat(off).contains(new OffWindow(Instant.parse("2030-01-07T10:30:00Z"), Instant.parse("2030-01-07T18:30:00Z")));
    }

    @Test
    void anOverrideExtendsTheEveningButCannotShortenIt() {
        ScheduleDto schedule = twoWindowsOnMonday("Override " + UUID.randomUUID());
        String base = "/api/v1/schedules/" + schedule.id();

        // Inside an existing window: no change.
        restTemplate.postForObject(base + "/overrides", new OverrideRequest("Narrow", LocalDate.parse("2030-01-07"),
                LocalDate.parse("2030-01-07"), LocalTime.of(19, 0), LocalTime.of(20, 0)), ScheduleDto.class);
        assertThat(restTemplate.getForObject(base + "/allowed?" + MONDAY, AllowedWindow[].class)).hasSize(2);

        // Running late: extends the evening window from 23:30 to midnight.
        restTemplate.postForObject(base + "/overrides", new OverrideRequest("Late", LocalDate.parse("2030-01-07"),
                LocalDate.parse("2030-01-07"), LocalTime.of(23, 30), LocalTime.MIDNIGHT), ScheduleDto.class);
        AllowedWindow[] allowed = restTemplate.getForObject(base + "/allowed?from=2030-01-07T12:00:00Z&days=1", AllowedWindow[].class);
        assertThat(allowed).containsExactly(
                new AllowedWindow(Instant.parse("2030-01-07T18:30:00Z"), Instant.parse("2030-01-08T00:00:00Z")));
    }

    @Test
    void aScheduleWithNoRowsRestrictsNothingEvenWhenAssigned() {
        ScheduleDto empty = restTemplate.postForObject("/api/v1/schedules",
                new ScheduleRequest("Empty " + UUID.randomUUID(), "Europe/Dublin", ScheduleAction.SLEEP, List.of()), ScheduleDto.class);
        UUID deviceId = registerDevice();
        assign(deviceId, empty.id());

        AgentSchedule schedule = heartbeat(deviceId).schedule();

        assertThat(schedule).isNotNull();
        assertThat(schedule.windows()).isEmpty();
    }

    // ---- blank times: no on-from = start of day, no off-at = midnight

    @Test
    void aLateNightIsFridayOnFrom18WithNoOffAndSaturdayOffAt01WithNoOn() {
        // 2030-01-04 is a Friday.
        ScheduleDto schedule = restTemplate.postForObject("/api/v1/schedules", new ScheduleRequest("Late " + UUID.randomUUID(),
                "Europe/Dublin", ScheduleAction.SLEEP, List.of(
                new Rule(List.of(DayOfWeek.FRIDAY), LocalTime.of(18, 0), null),
                new Rule(List.of(DayOfWeek.SATURDAY), null, LocalTime.of(1, 0)))), ScheduleDto.class);

        // Stored explicitly: Friday 18:00-00:00 and Saturday 00:00-01:00.
        assertThat(schedule.rules()).extracting(Rule::startTime, Rule::endTime).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(LocalTime.of(18, 0), LocalTime.MIDNIGHT),
                org.assertj.core.groups.Tuple.tuple(LocalTime.MIDNIGHT, LocalTime.of(1, 0)));
        AllowedWindow[] allowed = restTemplate.getForObject(
                "/api/v1/schedules/" + schedule.id() + "/allowed?from=2030-01-04T00:00:00Z&days=2", AllowedWindow[].class);
        assertThat(allowed).containsExactly(
                new AllowedWindow(Instant.parse("2030-01-04T18:00:00Z"), Instant.parse("2030-01-05T01:00:00Z")));
    }

    @Test
    void aRowWithNoTimesAtAllIsTheWholeDayAndABlankOverrideToo() {
        ScheduleDto schedule = restTemplate.postForObject("/api/v1/schedules", new ScheduleRequest("All day " + UUID.randomUUID(),
                "Europe/Dublin", ScheduleAction.SLEEP, List.of(new Rule(List.of(DayOfWeek.SATURDAY), null, null))), ScheduleDto.class);
        assertThat(schedule.rules().get(0).startTime()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(schedule.rules().get(0).endTime()).isEqualTo(LocalTime.MIDNIGHT);

        // 2030-01-05 is a Saturday.
        AllowedWindow[] sat = restTemplate.getForObject(
                "/api/v1/schedules/" + schedule.id() + "/allowed?from=2030-01-05T00:00:00Z&days=1", AllowedWindow[].class);
        assertThat(sat).containsExactly(new AllowedWindow(Instant.parse("2030-01-05T00:00:00Z"), Instant.parse("2030-01-06T00:00:00Z")));

        ScheduleDto withOverride = restTemplate.postForObject("/api/v1/schedules/" + schedule.id() + "/overrides",
                new OverrideRequest("Sunday too", LocalDate.parse("2030-01-06"), LocalDate.parse("2030-01-06"), null, null), ScheduleDto.class);
        assertThat(withOverride.overrides().get(0).startTime()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(restTemplate.getForObject("/api/v1/schedules/" + schedule.id() + "/allowed?from=2030-01-05T00:00:00Z&days=2",
                AllowedWindow[].class)).containsExactly(
                new AllowedWindow(Instant.parse("2030-01-05T00:00:00Z"), Instant.parse("2030-01-07T00:00:00Z")));
    }

    @Test
    void anExplicitWindowThatRunsPastMidnightIsStillRefused() {
        ScheduleRequest bad = new ScheduleRequest("Past " + UUID.randomUUID(), "Europe/Dublin", ScheduleAction.SLEEP,
                List.of(new Rule(List.of(DayOfWeek.FRIDAY), LocalTime.of(18, 0), LocalTime.of(1, 0))));
        ResponseEntity<ApiError> response = restTemplate.postForEntity("/api/v1/schedules", bad, ApiError.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("add a row for the next day");
    }
}
