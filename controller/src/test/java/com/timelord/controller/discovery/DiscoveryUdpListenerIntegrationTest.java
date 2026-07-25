package com.timelord.controller.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timelord.controller.support.AbstractIntegrationTest;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Talks to the real UDP listener the running Spring context bound (test
 * discovery port set in {@link AbstractIntegrationTest}), exactly as an
 * agent would, rather than unit-testing the listener class in isolation.
 */
class DiscoveryUdpListenerIntegrationTest extends AbstractIntegrationTest {

    private static final int TEST_DISCOVERY_PORT = 45922;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void validDiscoveryRequestReceivesControllerAvailableResponse() throws Exception {
        UUID requestId = UUID.randomUUID();
        Map<String, Object> request = Map.of(
                "protocol", "timelord-discovery",
                "protocolVersion", 1,
                "messageType", "DISCOVER_CONTROLLER",
                "requestId", requestId.toString(),
                "agentVersion", "0.1.0",
                "deviceId", UUID.randomUUID().toString(),
                "hostname", "test-pc"
        );

        Map<?, ?> response = sendAndReceive(request);

        assertThat(response.get("protocol")).isEqualTo("timelord-discovery");
        assertThat(response.get("messageType")).isEqualTo("CONTROLLER_AVAILABLE");
        assertThat(response.get("requestId")).isEqualTo(requestId.toString());
        assertThat(response.get("controllerId")).isNotNull();
        assertThat(response.get("controllerUrl")).isNotNull();
        assertThat(((Number) response.get("priority")).intValue()).isEqualTo(100);
    }

    @Test
    void requestWithWrongProtocolVersionGetsNoResponse() {
        Map<String, Object> request = Map.of(
                "protocol", "timelord-discovery",
                "protocolVersion", 999,
                "messageType", "DISCOVER_CONTROLLER",
                "requestId", UUID.randomUUID().toString(),
                "hostname", "test-pc"
        );

        assertThatThrownBy(() -> sendAndReceive(request)).isInstanceOf(SocketTimeoutException.class);
    }

    @Test
    void requestWithWrongProtocolNameGetsNoResponse() {
        Map<String, Object> request = Map.of(
                "protocol", "some-other-protocol",
                "protocolVersion", 1,
                "messageType", "DISCOVER_CONTROLLER",
                "requestId", UUID.randomUUID().toString(),
                "hostname", "test-pc"
        );

        assertThatThrownBy(() -> sendAndReceive(request)).isInstanceOf(SocketTimeoutException.class);
    }

    @Test
    void malformedPacketDoesNotCrashListenerAndSubsequentValidRequestStillWorks() throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(2000);
            byte[] garbage = "not json".getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(garbage, garbage.length, InetAddress.getLoopbackAddress(), TEST_DISCOVERY_PORT));
        }

        // Listener should have logged and ignored the garbage packet, not died.
        UUID requestId = UUID.randomUUID();
        Map<String, Object> request = Map.of(
                "protocol", "timelord-discovery",
                "protocolVersion", 1,
                "messageType", "DISCOVER_CONTROLLER",
                "requestId", requestId.toString(),
                "hostname", "test-pc"
        );
        Map<?, ?> response = sendAndReceive(request);
        assertThat(response.get("requestId")).isEqualTo(requestId.toString());
    }

    private Map<?, ?> sendAndReceive(Map<String, Object> request) throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(2000);
            byte[] payload = objectMapper.writeValueAsBytes(request);
            socket.send(new DatagramPacket(payload, payload.length, InetAddress.getLoopbackAddress(), TEST_DISCOVERY_PORT));

            byte[] buffer = new byte[4096];
            DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
            socket.receive(responsePacket);
            String json = new String(responsePacket.getData(), 0, responsePacket.getLength(), StandardCharsets.UTF_8);
            return objectMapper.readValue(json, Map.class);
        }
    }
}
