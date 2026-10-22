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

        // Sun-Thu 22:30, Fri-Sat 01:00, plus a blank spare row that must be ignored.
        ResponseEntity<String> saved = post("/calendars/" + id, form(
                "name", name, "timezone", "Europe/Dublin", "action", "SLEEP",
                "rules[0].days", "SUNDAY", "rules[0].days", "MONDAY", "rules[0].days", "TUESDAY",
                "rules[0].days", "WEDNESDAY", "rules[0].days", "THURSDAY",
                "rules[0].offTime", "22:30", "rules[0].onTime", "06:30",
                "rules[1].days", "FRIDAY", "rules[1].days", "SATURDAY",
                "rules[1].offTime", "01:00", "rules[1].onTime", "06:30",
                "rules[2].offTime", "", "rules[2].onTime", ""));
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.FOUND);

        CalendarDto calendar = restTemplate.getForObject("/api/v1/calendars/" + id, CalendarDto.class);
        assertThat(calendar.rules()).hasSize(2);

        // Single-day override: no end date.
        ResponseEntity<String> override = post("/calendars/" + id + "/overrides",
                form("name", "Party", "startDate", "2030-12-24", "endDate", "", "offTime", "23:30", "onTime", "06:30"));
        assertThat(override.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        calendar = restTemplate.getForObject("/api/v1/calendars/" + id, CalendarDto.class);
        assertThat(calendar.overrides()).hasSize(1);
        assertThat(calendar.overrides().get(0).endDate()).isEqualTo(calendar.overrides().get(0).startDate());

        String page = restTemplate.getForObject("/calendars/" + id, String.class);
        assertThat(page).contains(name).contains("Party").contains("22:30").contains("Next 14 days");

        post("/calendars/" + id + "/overrides/" + calendar.overrides().get(0).id() + "/delete", form());
        assertThat(restTemplate.getForObject("/api/v1/calendars/" + id, CalendarDto.class).overrides()).isEmpty();
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
                "rules[0].offTime", "22:30", "rules[0].onTime", "06:30"));

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
}
