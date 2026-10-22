package com.timelord.controller.message;

import static org.assertj.core.api.Assertions.assertThat;

import com.timelord.controller.agent.RegisterRequest;
import com.timelord.controller.common.ApiError;
import com.timelord.controller.device.NetworkInterfaceInfo;
import com.timelord.controller.support.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

class MessageApiIntegrationTest extends AbstractIntegrationTest {

    private UUID registerDevice() {
        UUID id = UUID.randomUUID();
        restTemplate.postForEntity("/api/v1/agents/register", new RegisterRequest(id, "Msg PC", "msg-pc", "0.1.0",
                "Windows 11", "10.0", "x86_64", List.of(),
                List.of(new NetworkInterfaceInfo("Ethernet", "AA:BB:CC:DD:EE:09", List.of()))), Object.class);
        return id;
    }

    private MessageDto[] poll(UUID deviceId, long after, int wait) {
        return restTemplate.getForObject("/api/v1/agents/" + deviceId + "/messages?after=" + after + "&waitSeconds=" + wait,
                MessageDto[].class);
    }

    @Test
    void sentMessageIsDeliveredOnceAndCursorSkipsIt() {
        UUID deviceId = registerDevice();
        restTemplate.postForEntity("/api/v1/devices/" + deviceId + "/messages",
                new MessageRequest("Heads up", "Dinner in 10", 60), MessageDto.class);

        MessageDto[] first = poll(deviceId, 0, 0);
        assertThat(first).hasSize(1);
        assertThat(first[0].body()).isEqualTo("Dinner in 10");
        assertThat(first[0].title()).isEqualTo("Heads up");

        assertThat(poll(deviceId, first[0].seq(), 0)).isEmpty();
        assertThat(restTemplate.getForObject("/api/v1/devices/" + deviceId + "/messages", MessageDto[].class)).hasSize(1);
    }

    @Test
    void parkedPollIsWokenBySend() throws Exception {
        UUID deviceId = registerDevice();
        CompletableFuture<MessageDto[]> parked = CompletableFuture.supplyAsync(() -> poll(deviceId, 0, 20));
        Thread.sleep(500);
        assertThat(parked).isNotDone();

        restTemplate.postForEntity("/api/v1/devices/" + deviceId + "/messages", new MessageRequest(null, "Now", null), MessageDto.class);

        MessageDto[] got = parked.get(5, TimeUnit.SECONDS);
        assertThat(got).hasSize(1);
        assertThat(got[0].body()).isEqualTo("Now");
    }

    @Test
    void emptyPollTimesOutWithEmptyList() {
        assertThat(poll(registerDevice(), 0, 1)).isEmpty();
    }

    @Test
    void invalidMessagesAreRejected() {
        UUID deviceId = registerDevice();
        assertThat(restTemplate.postForEntity("/api/v1/devices/" + deviceId + "/messages",
                new MessageRequest(null, "x".repeat(501), null), ApiError.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(restTemplate.postForEntity("/api/v1/devices/" + deviceId + "/messages",
                new MessageRequest(null, "   ", null), ApiError.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(restTemplate.postForEntity("/api/v1/devices/" + UUID.randomUUID() + "/messages",
                new MessageRequest(null, "hi", null), ApiError.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void broadcastReachesEveryDeviceAndFormsWork() {
        UUID a = registerDevice();
        UUID b = registerDevice();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("body", "Everyone: lights out soon");
        form.add("expiresInMinutes", "60");
        assertThat(restTemplate.postForEntity("/messages", new HttpEntity<>(form, headers), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FOUND);

        assertThat(poll(a, 0, 0)).extracting(MessageDto::body).contains("Everyone: lights out soon");
        assertThat(poll(b, 0, 0)).extracting(MessageDto::body).contains("Everyone: lights out soon");

        assertThat(restTemplate.postForEntity("/devices/" + a + "/messages", new HttpEntity<>(form, headers), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(restTemplate.getForObject("/devices/" + a, String.class)).contains("Send a message").contains("delivered");
    }

    @Test
    void deletingDeviceRemovesItsMessages() {
        UUID deviceId = registerDevice();
        restTemplate.postForEntity("/api/v1/devices/" + deviceId + "/messages", new MessageRequest(null, "bye", null), MessageDto.class);

        restTemplate.delete("/api/v1/devices/" + deviceId);

        assertThat(restTemplate.getForEntity("/api/v1/devices/" + deviceId + "/messages", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/api/v1/devices/" + deviceId, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
