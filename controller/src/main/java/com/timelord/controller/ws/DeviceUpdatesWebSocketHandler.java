package com.timelord.controller.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timelord.controller.device.Device;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Pushes a small "something changed" message to every connected dashboard
 * whenever a device sends a heartbeat, registers, or the
 * {@link com.timelord.controller.device.DeviceService#recomputeOnlineStatus()}
 * scheduler decides a device has gone offline. The browser side treats
 * receipt of any message as a cue to re-fetch, not as the full state itself
 * — this only needs to say "look again", not carry the whole device list.
 */
@Component
public class DeviceUpdatesWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DeviceUpdatesWebSocketHandler.class);

    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final ObjectMapper objectMapper;

    public DeviceUpdatesWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    public void broadcastDeviceChanged(Device device, String reason) {
        broadcast(Map.of(
                "deviceId", device.getId().toString(),
                "status", device.getStatus().name(),
                "reason", reason,
                "at", Instant.now().toString()));
    }

    private void broadcast(Object payload) {
        if (sessions.isEmpty()) {
            return;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            log.warn("Failed to serialize device update for broadcast: {}", e.getMessage());
            return;
        }
        TextMessage message = new TextMessage(json);
        for (WebSocketSession session : sessions) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(message);
                }
            } catch (IOException e) {
                log.debug("Failed to send device update to session {}: {}", session.getId(), e.getMessage());
            }
        }
    }
}
