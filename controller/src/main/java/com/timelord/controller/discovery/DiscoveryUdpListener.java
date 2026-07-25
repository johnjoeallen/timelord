package com.timelord.controller.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timelord.controller.config.DiscoveryProperties;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Listens for {@code DISCOVER_CONTROLLER} broadcasts and replies directly
 * (unicast) to the sender with {@code CONTROLLER_AVAILABLE}. Deliberately
 * does not touch the device table — a discovery request is not a
 * registration (design brief section 13).
 */
@Component
public class DiscoveryUdpListener {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryUdpListener.class);

    private final DiscoveryProperties discoveryProperties;
    private final ControllerIdentityService identityService;
    private final ObjectMapper objectMapper;

    private DatagramSocket socket;
    private Thread listenerThread;
    private volatile boolean running;
    private volatile boolean healthy;

    public DiscoveryUdpListener(DiscoveryProperties discoveryProperties,
                                 ControllerIdentityService identityService,
                                 ObjectMapper objectMapper) {
        this.discoveryProperties = discoveryProperties;
        this.identityService = identityService;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!discoveryProperties.enabled()) {
            log.info("UDP discovery listener disabled (timelord.discovery.enabled=false)");
            healthy = true;
            return;
        }
        try {
            socket = new DatagramSocket(discoveryProperties.port());
            running = true;
            healthy = true;
            listenerThread = new Thread(this::listenLoop, "timelord-discovery-listener");
            listenerThread.setDaemon(true);
            listenerThread.start();
            log.info("UDP discovery listener bound to port {}", discoveryProperties.port());
        } catch (SocketException e) {
            healthy = false;
            log.error("Failed to bind UDP discovery listener on port {}: {}", discoveryProperties.port(), e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (socket != null) {
            socket.close();
        }
    }

    private void listenLoop() {
        byte[] buffer = new byte[DiscoveryConstants.MAX_PACKET_SIZE];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                handlePacket(packet);
            } catch (IOException e) {
                if (running) {
                    log.warn("Error receiving discovery packet: {}", e.getMessage());
                }
            }
        }
    }

    private void handlePacket(DatagramPacket packet) {
        try {
            String json = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
            DiscoverRequestMessage request = objectMapper.readValue(json, DiscoverRequestMessage.class);
            if (!isValidRequest(request)) {
                log.debug("Ignoring invalid/unsupported discovery request from {}", packet.getSocketAddress());
                return;
            }
            log.debug("Discovery request {} from hostname={} ({})", request.requestId(), request.hostname(),
                    packet.getSocketAddress());

            ControllerAvailableMessage response = ControllerAvailableMessage.forRequest(
                    request.requestId(), identityService.controllerId(), identityService.controllerName(),
                    identityService.publicUrl(), identityService.priority());
            byte[] responseBytes = objectMapper.writeValueAsBytes(response);
            socket.send(new DatagramPacket(responseBytes, responseBytes.length, packet.getAddress(), packet.getPort()));
        } catch (Exception e) {
            // Malformed/foreign UDP traffic on this port is expected background
            // noise, not a failure of the listener itself.
            log.debug("Failed to handle discovery packet from {}: {}", packet.getSocketAddress(), e.toString());
        }
    }

    private boolean isValidRequest(DiscoverRequestMessage request) {
        return request != null
                && DiscoveryConstants.PROTOCOL_NAME.equals(request.protocol())
                && request.protocolVersion() == DiscoveryConstants.PROTOCOL_VERSION
                && DiscoveryConstants.TYPE_DISCOVER_CONTROLLER.equals(request.messageType())
                && request.requestId() != null;
    }

    public boolean isHealthy() {
        return healthy;
    }

    public boolean isEnabled() {
        return discoveryProperties.enabled();
    }
}
