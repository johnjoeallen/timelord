package com.timelord.controller.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timelord.controller.config.DiscoveryProperties;
import jakarta.annotation.PreDestroy;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Optional periodic {@code CONTROLLER_ANNOUNCEMENT} broadcast (design brief
 * section 4.1) — off by default. Active agent discovery
 * ({@link DiscoveryUdpListener}) is the primary mechanism; this is a
 * secondary convenience for agents with no configured controller that are
 * just listening passively. A limited (255.255.255.255) broadcast normally
 * doesn't cross routers or reach into a Docker bridge network unless
 * explicitly forwarded — see deployment/README for details.
 */
@Component
public class DiscoveryAnnouncer {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryAnnouncer.class);
    private static final String BROADCAST_ADDRESS = "255.255.255.255";

    private final DiscoveryProperties discoveryProperties;
    private final ControllerIdentityService identityService;
    private final ObjectMapper objectMapper;

    private ScheduledExecutorService executor;

    public DiscoveryAnnouncer(DiscoveryProperties discoveryProperties,
                               ControllerIdentityService identityService,
                               ObjectMapper objectMapper) {
        this.discoveryProperties = discoveryProperties;
        this.identityService = identityService;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!discoveryProperties.enabled() || !discoveryProperties.announcementEnabled()) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "timelord-discovery-announcer");
            thread.setDaemon(true);
            return thread;
        });
        long intervalMillis = discoveryProperties.announcementInterval().toMillis();
        executor.scheduleWithFixedDelay(this::announceOnce, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        log.info("Controller announcements enabled every {}", discoveryProperties.announcementInterval());
    }

    @PreDestroy
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void announceOnce() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            ControllerAnnouncementMessage message = ControllerAnnouncementMessage.now(
                    identityService.controllerId(), identityService.controllerName(),
                    identityService.publicUrl(), identityService.priority());
            byte[] bytes = objectMapper.writeValueAsBytes(message);
            InetAddress broadcastAddress = InetAddress.getByName(BROADCAST_ADDRESS);
            socket.send(new DatagramPacket(bytes, bytes.length, broadcastAddress, discoveryProperties.port()));
        } catch (Exception e) {
            log.warn("Failed to send discovery announcement: {}", e.getMessage());
        }
    }
}
