package com.timelord.controller.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.timelord.controller.agent.RegisterRequest;
import com.timelord.controller.calendar.CalendarDto;
import com.timelord.controller.device.NetworkInterfaceInfo;
import com.timelord.controller.support.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

class CalendarDashboardIntegrationTest extends AbstractIntegrationTest {

    private ResponseEntity<String> post(String path, MultiValueMap<String, String> form) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return restTemplate.postForEntity(path, new HttpEntity<>(form, headers), String.class);
    }

    private static MultiValueMap<String, String> form(String... kv) {
        MultiValueMap<String, String> m = new LinkedMultiValueMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.add(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static UUID idFromRedirect(ResponseEntity<String> response) {
        String path = response.getHeaders().getLocation().getPath();
        // Flash attributes use a session, so the redirect may carry a ;jsessionid suffix.
        return UUID.fromString(path.substring(path.lastIndexOf('/') + 1).split(";")[0]);
    }

    @Test
    void createEditAndOverrideCalendarThroughForms() {
        assertThat(restTemplate.getForEntity("/calendars", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        String name = "Kids " + UUID.randomUUID();
        ResponseEntity<String> created = post("/calendars", form("name", name, "timezone", "Europe/Dublin", "action", "SLEEP"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        UUID id = idFromRedirect(created);

        // Two rows covering the same weekdays, plus a blank spare row that must be ignored.
        ResponseEntity<String> saved = post("/calendars/" + id, form(
                "name", name, "timezone", "Europe/Dublin", "action", "SLEEP",
                "rules[0].days", "MONDAY", "rules[0].days", "TUESDAY", "rules[0].days", "WEDNESDAY",
                "rules[0].days", "THURSDAY", "rules[0].days", "FRIDAY",
                "rules[0].startTime", "08:30", "rules[0].endTime", "10:30",
                "rules[1].days", "MONDAY", "rules[1].days", "TUESDAY", "rules[1].days", "WEDNESDAY",
                "rules[1].days", "THURSDAY", "rules[1].days", "FRIDAY",
                "rules[1].startTime", "18:30", "rules[1].endTime", "23:30",
                "rules[2].startTime", "", "rules[2].endTime", ""));
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.FOUND);

        CalendarDto calendar = restTemplate.getForObject("/api/v1/calendars/" + id, CalendarDto.class);
        assertThat(calendar.rules()).hasSize(2);

        // Single-day override: no end date.
        ResponseEntity<String> override = post("/calendars/" + id + "/overrides",
                form("name", "Party", "startDate", "2030-12-24", "endDate", "", "startTime", "23:30", "endTime", "00:00"));
        assertThat(override.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        calendar = restTemplate.getForObject("/api/v1/calendars/" + id, CalendarDto.class);
        assertThat(calendar.overrides()).hasSize(1);
        assertThat(calendar.overrides().get(0).endDate()).isEqualTo(calendar.overrides().get(0).startDate());

        String page = restTemplate.getForObject("/calendars/" + id, String.class);
        assertThat(page).contains(name).contains("Party").contains("08:30").contains("18:30")
                .contains("Allowed in the next 14 days").contains("Next: ");

        post("/calendars/" + id + "/overrides/" + calendar.overrides().get(0).id() + "/delete", form());
        assertThat(restTemplate.getForObject("/api/v1/calendars/" + id, CalendarDto.class).overrides()).isEmpty();
    }

    @Test
    void rowsCanBeDisabledAndAreKeptButIgnored() {
        String name = "Toggle " + UUID.randomUUID();
        UUID id = idFromRedirect(post("/calendars", form("name", name, "timezone", "Europe/Dublin", "action", "SLEEP")));

        post("/calendars/" + id, form("name", name, "timezone", "Europe/Dublin", "action", "SLEEP",
                "rules[0].enabled", "true", "rules[0].days", "MONDAY", "rules[0].startTime", "08:00", "rules[0].endTime", "10:00",
                "rules[1].enabled", "false", "rules[1].days", "MONDAY", "rules[1].startTime", "18:00", "rules[1].endTime", "20:00",
                "rules[2].enabled", "true", "rules[2].startTime", "", "rules[2].endTime", ""));

        CalendarDto calendar = restTemplate.getForObject("/api/v1/calendars/" + id, CalendarDto.class);
        assertThat(calendar.rules()).hasSize(2);
        assertThat(calendar.rules()).extracting(CalendarDto.Rule::enabled).containsExactlyInAnyOrder(true, false);

        String page = restTemplate.getForObject("/calendars/" + id, String.class);
        assertThat(page).contains("rule-disabled");
        // Exactly one entry row, always at the bottom.
        assertThat(page.split("class=\"rule-row rule-blank", -1)).hasSize(2);
        // The disabled 18:00 row gives no window.
        assertThat(restTemplate.getForObject("/api/v1/calendars/" + id + "/allowed?days=14", String.class)).doesNotContain("18:00");
    }

    @Test
    void aRowWithTimesButNoDaysIsRefusedNotDropped() {
        String name = "NoDays " + UUID.randomUUID();
        UUID id = idFromRedirect(post("/calendars", form("name", name, "timezone", "Europe/Dublin", "action", "SLEEP")));

        post("/calendars/" + id, form("name", name, "timezone", "Europe/Dublin", "action", "SLEEP",
                "rules[0].startTime", "08:00", "rules[0].endTime", "10:00"));

        assertThat(restTemplate.getForObject("/api/v1/calendars/" + id, CalendarDto.class).rules()).isEmpty();
    }

    @Test
    void assignAndExtendFromDevicePage() {
        UUID deviceId = UUID.randomUUID();
        restTemplate.postForEntity("/api/v1/agents/register", new RegisterRequest(deviceId, "Kid PC", "kid-pc", "0.1.0",
                "Windows 11", "10.0", "x86_64", List.of("192.168.1.70"),
                List.of(new NetworkInterfaceInfo("Ethernet", "AA:BB:CC:DD:EE:02", List.of("192.168.1.70")))), Object.class);
        String name = "Nightly " + UUID.randomUUID();
        UUID calendarId = idFromRedirect(post("/calendars", form("name", name, "timezone", "Europe/Dublin", "action", "SHUTDOWN")));
        post("/calendars/" + calendarId, form("name", name, "timezone", "Europe/Dublin", "action", "SHUTDOWN",
                "rules[0].days", "MONDAY", "rules[0].days", "TUESDAY", "rules[0].days", "WEDNESDAY", "rules[0].days", "THURSDAY",
                "rules[0].days", "FRIDAY", "rules[0].days", "SATURDAY", "rules[0].days", "SUNDAY",
                "rules[0].startTime", "06:30", "rules[0].endTime", "22:30"));

        assertThat(post("/devices/" + deviceId + "/calendar", form("calendarId", calendarId.toString())).getStatusCode())
                .isEqualTo(HttpStatus.FOUND);
        assertThat(post("/devices/" + deviceId + "/extend", form("minutes", "30")).getStatusCode()).isEqualTo(HttpStatus.FOUND);

        String page = restTemplate.getForObject("/devices/" + deviceId, String.class);
        assertThat(page).contains(name).contains("Extended until").contains("Upcoming off times");

        post("/devices/" + deviceId + "/extend/clear", form());
        assertThat(restTemplate.getForObject("/devices/" + deviceId, String.class)).doesNotContain("Extended until");

        post("/devices/" + deviceId + "/calendar", form("calendarId", ""));
        assertThat(restTemplate.getForObject("/devices/" + deviceId, String.class)).doesNotContain("Upcoming off times");
    }

    @Test
    void deletingDeviceRemovesItsHistory() {
        UUID deviceId = UUID.randomUUID();
        restTemplate.postForEntity("/api/v1/agents/register", new RegisterRequest(deviceId, "Old PC", "old-pc", "0.1.0",
                "Windows 11", "10.0", "x86_64", List.of("192.168.1.80"),
                List.of(new NetworkInterfaceInfo("Ethernet", "AA:BB:CC:DD:EE:03", List.of("192.168.1.80")))), Object.class);
        restTemplate.postForEntity("/api/v1/agents/" + deviceId + "/heartbeat", new com.timelord.controller.agent.HeartbeatRequest(
                UUID.randomUUID(), java.time.Instant.now(), "0.1.0", 1L, "RUNNING", null, null, null, null), Object.class);
        restTemplate.postForEntity("/api/v1/agents/" + deviceId + "/events", java.util.Map.of("events", List.of(java.util.Map.of(
                "eventId", UUID.randomUUID().toString(), "eventType", "AGENT_STARTED", "occurredAt", java.time.Instant.now().toString(),
                "severity", "INFO", "source", "AGENT", "data", java.util.Map.of()))), Object.class);

        assertThat(post("/devices/" + deviceId + "/delete", form()).getStatusCode()).isEqualTo(HttpStatus.FOUND);

        assertThat(restTemplate.getForEntity("/api/v1/devices/" + deviceId, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.getForEntity("/api/v1/devices/" + deviceId + "/events", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteApiRemovesDeviceAndMissingIs404() {
        UUID deviceId = UUID.randomUUID();
        restTemplate.postForEntity("/api/v1/agents/register", new RegisterRequest(deviceId, "Tmp", "tmp", "0.1.0",
                "Windows 11", "10.0", "x86_64", List.of(), List.of()), Object.class);

        assertThat(restTemplate.exchange("/api/v1/devices/" + deviceId, org.springframework.http.HttpMethod.DELETE, null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(restTemplate.exchange("/api/v1/devices/" + deviceId, org.springframework.http.HttpMethod.DELETE, null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void offWindowWithinADayAsTwoRowsAndAMidnightEndThroughForms() {
        String name = "Thursday " + UUID.randomUUID();
        UUID id = idFromRedirect(post("/calendars", form("name", name, "timezone", "Europe/Dublin", "action", "SLEEP")));
        // "Off 16:15, on 16:30" on Thursdays: on 00:00-16:15 and on 16:30-midnight (00:00 as the end means midnight).
        post("/calendars/" + id, form("name", name, "timezone", "Europe/Dublin", "action", "SLEEP",
                "rules[0].days", "THURSDAY", "rules[0].startTime", "00:00", "rules[0].endTime", "16:15",
                "rules[1].days", "THURSDAY", "rules[1].startTime", "16:30", "rules[1].endTime", "00:00"));
        assertThat(restTemplate.getForObject("/api/v1/calendars/" + id, CalendarDto.class).rules()).hasSize(2);

        // 2030-01-10 is a Thursday (UTC+0 in January in Dublin).
        var off = restTemplate.getForObject("/api/v1/calendars/" + id + "/windows?from=2030-01-10T09:00:00Z&days=1",
                com.timelord.controller.calendar.OffWindow[].class);
        assertThat(off).anySatisfy(w -> {
            assertThat(w.offAt()).isEqualTo(java.time.Instant.parse("2030-01-10T16:15:00Z"));
            assertThat(w.onAt()).isEqualTo(java.time.Instant.parse("2030-01-10T16:30:00Z"));
        });

        // A row that would run past midnight is refused with a message, not saved.
        ResponseEntity<String> refused = post("/calendars/" + id, form("name", name, "timezone", "Europe/Dublin", "action", "SLEEP",
                "rules[0].days", "FRIDAY", "rules[0].startTime", "18:30", "rules[0].endTime", "01:00"));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(restTemplate.getForObject("/api/v1/calendars/" + id, CalendarDto.class).rules()).hasSize(2);
    }

    @Test
    void blankFormTimesDefaultToTheStartAndEndOfTheDay() {
        String name = "Blanks " + UUID.randomUUID();
        UUID id = idFromRedirect(post("/calendars", form("name", name, "timezone", "Europe/Dublin", "action", "SLEEP")));
        // Friday: on from 18:00, off at left blank. Saturday: on from left blank, off at 01:00.
        post("/calendars/" + id, form("name", name, "timezone", "Europe/Dublin", "action", "SLEEP",
                "rules[0].days", "FRIDAY", "rules[0].startTime", "18:00", "rules[0].endTime", "",
                "rules[1].days", "SATURDAY", "rules[1].startTime", "", "rules[1].endTime", "01:00",
                "rules[2].days", "SUNDAY", "rules[2].startTime", "", "rules[2].endTime", ""));

        CalendarDto calendar = restTemplate.getForObject("/api/v1/calendars/" + id, CalendarDto.class);

        assertThat(calendar.rules()).extracting(CalendarDto.Rule::startTime, CalendarDto.Rule::endTime).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(java.time.LocalTime.of(18, 0), java.time.LocalTime.MIDNIGHT),
                org.assertj.core.groups.Tuple.tuple(java.time.LocalTime.MIDNIGHT, java.time.LocalTime.of(1, 0)),
                org.assertj.core.groups.Tuple.tuple(java.time.LocalTime.MIDNIGHT, java.time.LocalTime.MIDNIGHT));

        // An override with both times blank is a whole extra day.
        post("/calendars/" + id + "/overrides", form("name", "Open day", "startDate", "2030-02-04", "endDate", "", "startTime", "", "endTime", ""));
        assertThat(restTemplate.getForObject("/api/v1/calendars/" + id, CalendarDto.class).overrides()).hasSize(1);
    }
}
