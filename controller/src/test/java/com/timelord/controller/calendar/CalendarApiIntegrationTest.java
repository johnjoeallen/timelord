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

    /** Allowed 06:30 to 22:30 every day, so there is an off window every night. */
    private CalendarRequest request(String name) {
        return new CalendarRequest(name, "Europe/Dublin", CalendarAction.SLEEP, List.of(
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

    private void assign(UUID deviceId, UUID calendarId) {
        restTemplate.exchange("/api/v1/devices/" + deviceId + "/calendar", HttpMethod.PUT,
                new HttpEntity<>(new CalendarAssignment(calendarId)), DeviceDetail.class);
    }

    @Test
    void anOverrideCanBeDisabledThroughTheApi() {
        CalendarDto created = restTemplate.postForObject("/api/v1/calendars", request("Toggle " + UUID.randomUUID()), CalendarDto.class);
        CalendarDto withOverride = restTemplate.postForObject("/api/v1/calendars/" + created.id() + "/overrides",
                new OverrideRequest("Holiday", java.time.LocalDate.parse("2030-02-01"), java.time.LocalDate.parse("2030-02-03"),
                        LocalTime.of(8, 0), LocalTime.of(23, 0)), CalendarDto.class);
        UUID overrideId = withOverride.overrides().get(0).id();
        assertThat(withOverride.overrides().get(0).enabled()).isTrue();

        CalendarDto off = restTemplate.exchange("/api/v1/calendars/" + created.id() + "/overrides/" + overrideId + "/enabled",
                HttpMethod.PUT, new HttpEntity<>(new EnabledRequest(false)), CalendarDto.class).getBody();

        assertThat(off.overrides().get(0).enabled()).isFalse();
        assertThat(restTemplate.exchange("/api/v1/calendars/" + created.id() + "/overrides/" + UUID.randomUUID() + "/enabled",
                HttpMethod.PUT, new HttpEntity<>(new EnabledRequest(true)), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
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
                new OverrideRequest("Holiday", today, today.plusDays(30), LocalTime.of(6, 30), LocalTime.MIDNIGHT),
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

        CalendarRequest noDays = new CalendarRequest("No days " + UUID.randomUUID(), "Europe/Dublin", CalendarAction.SLEEP, List.of(
                new Rule(List.of(), LocalTime.of(8, 0), LocalTime.of(9, 0))));
        assertThat(restTemplate.postForEntity("/api/v1/calendars", noDays, ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // A window can't run past midnight: 18:30 -> 01:00 is rejected, 18:30 -> 00:00 is fine.
        CalendarRequest pastMidnight = new CalendarRequest("Past midnight " + UUID.randomUUID(), "Europe/Dublin", CalendarAction.SLEEP,
                List.of(new Rule(List.of(DayOfWeek.FRIDAY), LocalTime.of(18, 30), LocalTime.of(1, 0))));
        assertThat(restTemplate.postForEntity("/api/v1/calendars", pastMidnight, ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        CalendarRequest toMidnight = new CalendarRequest("To midnight " + UUID.randomUUID(), "Europe/Dublin", CalendarAction.SLEEP,
                List.of(new Rule(List.of(DayOfWeek.FRIDAY), LocalTime.of(18, 30), LocalTime.MIDNIGHT)));
        assertThat(restTemplate.postForEntity("/api/v1/calendars", toMidnight, CalendarDto.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        CalendarDto calendar = restTemplate.postForObject("/api/v1/calendars", request("Bad override " + UUID.randomUUID()), CalendarDto.class);
        assertThat(restTemplate.postForEntity("/api/v1/calendars/" + calendar.id() + "/overrides",
                new OverrideRequest(null, LocalDate.now(), LocalDate.now(), LocalTime.of(18, 30), LocalTime.of(1, 0)), ApiError.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        LocalDate day = LocalDate.now();
        assertThat(restTemplate.postForEntity("/api/v1/calendars/" + calendar.id() + "/overrides",
                new OverrideRequest(null, day, day.minusDays(1), LocalTime.of(8, 0), LocalTime.of(9, 0)), ApiError.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
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
        assign(deviceId, calendar.id());

        restTemplate.delete("/api/v1/calendars/" + calendar.id());

        assertThat(restTemplate.getForObject("/api/v1/devices/" + deviceId, DeviceDetail.class).calendarId()).isNull();
        assertThat(heartbeat(deviceId).schedule()).isNull();
    }

    @Test
    void extendingDelaysNextWindowAndWakesLongPollingAgent() throws Exception {
        CalendarDto calendar = restTemplate.postForObject("/api/v1/calendars", request("Extend " + UUID.randomUUID()), CalendarDto.class);
        UUID deviceId = registerDevice();
        assign(deviceId, calendar.id());
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
        // Base calendar is unchanged.
        assertThat(restTemplate.getForObject("/api/v1/calendars/" + calendar.id(), CalendarDto.class).rules())
                .isEqualTo(calendar.rules());
    }

    @Test
    void longPollWithCurrentRevisionTimesOutWithSameSchedule() {
        CalendarDto calendar = restTemplate.postForObject("/api/v1/calendars", request("Poll " + UUID.randomUUID()), CalendarDto.class);
        UUID deviceId = registerDevice();
        assign(deviceId, calendar.id());
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

    // ---- allowed windows: several rows on a day, and overrides that only extend

    /** 2030-01-07 is a Monday; Europe/Dublin is UTC+0 in January, so local times equal the instants. */
    private static final String MONDAY = "from=2030-01-07T00:00:00Z&days=1";

    private CalendarDto twoWindowsOnMonday(String name) {
        return restTemplate.postForObject("/api/v1/calendars", new CalendarRequest(name, "Europe/Dublin", CalendarAction.SLEEP, List.of(
                new Rule(List.of(DayOfWeek.MONDAY), LocalTime.of(8, 30), LocalTime.of(10, 30)),
                new Rule(List.of(DayOfWeek.MONDAY), LocalTime.of(18, 30), LocalTime.of(23, 30)))), CalendarDto.class);
    }

    @Test
    void severalRowsCoveringTheSameDayAreAcceptedAndBothApply() {
        CalendarDto calendar = twoWindowsOnMonday("Two windows " + UUID.randomUUID());
        assertThat(calendar.rules()).hasSize(2);

        AllowedWindow[] allowed = restTemplate.getForObject("/api/v1/calendars/" + calendar.id() + "/allowed?" + MONDAY, AllowedWindow[].class);

        assertThat(allowed).containsExactly(
                new AllowedWindow(Instant.parse("2030-01-07T08:30:00Z"), Instant.parse("2030-01-07T10:30:00Z")),
                new AllowedWindow(Instant.parse("2030-01-07T18:30:00Z"), Instant.parse("2030-01-07T23:30:00Z")));
        OffWindow[] off = restTemplate.getForObject("/api/v1/calendars/" + calendar.id() + "/windows?" + MONDAY, OffWindow[].class);
        assertThat(off).contains(new OffWindow(Instant.parse("2030-01-07T10:30:00Z"), Instant.parse("2030-01-07T18:30:00Z")));
    }

    @Test
    void anOverrideExtendsTheEveningButCannotShortenIt() {
        CalendarDto calendar = twoWindowsOnMonday("Override " + UUID.randomUUID());
        String base = "/api/v1/calendars/" + calendar.id();

        // Inside an existing window: no change.
        restTemplate.postForObject(base + "/overrides", new OverrideRequest("Narrow", LocalDate.parse("2030-01-07"),
                LocalDate.parse("2030-01-07"), LocalTime.of(19, 0), LocalTime.of(20, 0)), CalendarDto.class);
        assertThat(restTemplate.getForObject(base + "/allowed?" + MONDAY, AllowedWindow[].class)).hasSize(2);

        // Running late: extends the evening window from 23:30 to midnight.
        restTemplate.postForObject(base + "/overrides", new OverrideRequest("Late", LocalDate.parse("2030-01-07"),
                LocalDate.parse("2030-01-07"), LocalTime.of(23, 30), LocalTime.MIDNIGHT), CalendarDto.class);
        AllowedWindow[] allowed = restTemplate.getForObject(base + "/allowed?from=2030-01-07T12:00:00Z&days=1", AllowedWindow[].class);
        assertThat(allowed).containsExactly(
                new AllowedWindow(Instant.parse("2030-01-07T18:30:00Z"), Instant.parse("2030-01-08T00:00:00Z")));
    }

    @Test
    void aCalendarWithNoRowsRestrictsNothingEvenWhenAssigned() {
        CalendarDto empty = restTemplate.postForObject("/api/v1/calendars",
                new CalendarRequest("Empty " + UUID.randomUUID(), "Europe/Dublin", CalendarAction.SLEEP, List.of()), CalendarDto.class);
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
        CalendarDto calendar = restTemplate.postForObject("/api/v1/calendars", new CalendarRequest("Late " + UUID.randomUUID(),
                "Europe/Dublin", CalendarAction.SLEEP, List.of(
                new Rule(List.of(DayOfWeek.FRIDAY), LocalTime.of(18, 0), null),
                new Rule(List.of(DayOfWeek.SATURDAY), null, LocalTime.of(1, 0)))), CalendarDto.class);

        // Stored explicitly: Friday 18:00-00:00 and Saturday 00:00-01:00.
        assertThat(calendar.rules()).extracting(Rule::startTime, Rule::endTime).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(LocalTime.of(18, 0), LocalTime.MIDNIGHT),
                org.assertj.core.groups.Tuple.tuple(LocalTime.MIDNIGHT, LocalTime.of(1, 0)));
        AllowedWindow[] allowed = restTemplate.getForObject(
                "/api/v1/calendars/" + calendar.id() + "/allowed?from=2030-01-04T00:00:00Z&days=2", AllowedWindow[].class);
        assertThat(allowed).containsExactly(
                new AllowedWindow(Instant.parse("2030-01-04T18:00:00Z"), Instant.parse("2030-01-05T01:00:00Z")));
    }

    @Test
    void aRowWithNoTimesAtAllIsTheWholeDayAndABlankOverrideToo() {
        CalendarDto calendar = restTemplate.postForObject("/api/v1/calendars", new CalendarRequest("All day " + UUID.randomUUID(),
                "Europe/Dublin", CalendarAction.SLEEP, List.of(new Rule(List.of(DayOfWeek.SATURDAY), null, null))), CalendarDto.class);
        assertThat(calendar.rules().get(0).startTime()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(calendar.rules().get(0).endTime()).isEqualTo(LocalTime.MIDNIGHT);

        // 2030-01-05 is a Saturday.
        AllowedWindow[] sat = restTemplate.getForObject(
                "/api/v1/calendars/" + calendar.id() + "/allowed?from=2030-01-05T00:00:00Z&days=1", AllowedWindow[].class);
        assertThat(sat).containsExactly(new AllowedWindow(Instant.parse("2030-01-05T00:00:00Z"), Instant.parse("2030-01-06T00:00:00Z")));

        CalendarDto withOverride = restTemplate.postForObject("/api/v1/calendars/" + calendar.id() + "/overrides",
                new OverrideRequest("Sunday too", LocalDate.parse("2030-01-06"), LocalDate.parse("2030-01-06"), null, null), CalendarDto.class);
        assertThat(withOverride.overrides().get(0).startTime()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(restTemplate.getForObject("/api/v1/calendars/" + calendar.id() + "/allowed?from=2030-01-05T00:00:00Z&days=2",
                AllowedWindow[].class)).containsExactly(
                new AllowedWindow(Instant.parse("2030-01-05T00:00:00Z"), Instant.parse("2030-01-07T00:00:00Z")));
    }

    @Test
    void anExplicitWindowThatRunsPastMidnightIsStillRefused() {
        CalendarRequest bad = new CalendarRequest("Past " + UUID.randomUUID(), "Europe/Dublin", CalendarAction.SLEEP,
                List.of(new Rule(List.of(DayOfWeek.FRIDAY), LocalTime.of(18, 0), LocalTime.of(1, 0))));
        ResponseEntity<ApiError> response = restTemplate.postForEntity("/api/v1/calendars", bad, ApiError.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("add a row for the next day");
    }
}
