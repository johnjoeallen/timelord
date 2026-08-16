package com.timelord.controller.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final DeviceUpdatesWebSocketHandler deviceUpdatesWebSocketHandler;

    public WebSocketConfig(DeviceUpdatesWebSocketHandler deviceUpdatesWebSocketHandler) {
        this.deviceUpdatesWebSocketHandler = deviceUpdatesWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(deviceUpdatesWebSocketHandler, "/ws/devices");
    }
}
