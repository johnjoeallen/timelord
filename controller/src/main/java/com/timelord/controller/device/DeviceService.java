package com.timelord.controller.device;

import com.timelord.controller.agent.HeartbeatRequest;
import com.timelord.controller.agent.HeartbeatResponse;
import com.timelord.controller.agent.RegisterRequest;
import com.timelord.controller.agent.RegisterResponse;
import com.timelord.controller.common.ApiException;
import com.timelord.controller.config.AgentDefaultsProperties;
import com.timelord.controller.config.DeviceProperties;
import com.timelord.controller.discovery.ControllerIdentityService;
import com.timelord.controller.event.DeviceHeartbeat;
import com.timelord.controller.event.DeviceHeartbeatRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final DeviceRepository deviceRepository;
    private final DeviceHeartbeatRepository heartbeatRepository;
    private final ControllerIdentityService controllerIdentityService;
    private final AgentDefaultsProperties agentDefaults;
    private final DeviceProperties deviceProperties;

    public DeviceService(DeviceRepository deviceRepository,
                          DeviceHeartbeatRepository heartbeatRepository,
                          ControllerIdentityService controllerIdentityService,
                          AgentDefaultsProperties agentDefaults,
                          DeviceProperties deviceProperties) {
        this.deviceRepository = deviceRepository;
        this.heartbeatRepository = heartbeatRepository;
        this.controllerIdentityService = controllerIdentityService;
        this.agentDefaults = agentDefaults;
        this.deviceProperties = deviceProperties;
    }

    /** Idempotent by deviceId: repeated registration updates metadata rather than creating a new row. */
    @Transactional
    public RegisterResponse register(RegisterRequest request, String sourceIp) {
        Instant now = Instant.now();
        boolean isNew = !deviceRepository.existsById(request.deviceId());
        Device device = deviceRepository.findById(request.deviceId())
                .orElseGet(() -> new Device(request.deviceId()));

        if (isNew) {
            device.setRegisteredAt(now);
            device.setCreatedAt(now);
        }
        device.setDeviceName(request.deviceName());
        device.setHostname(request.hostname());
        device.setAgentVersion(request.agentVersion());
        device.setOperatingSystem(request.operatingSystem());
        device.setOperatingSystemVersion(request.operatingSystemVersion());
        device.setArchitecture(request.architecture());
        device.setLocalIpAddresses(request.localIpAddresses());
        device.setSourceIp(sourceIp);
        device.setStatus(DeviceStatus.ONLINE);
        device.setLastRegistrationAt(now);
        device.setUpdatedAt(now);

        deviceRepository.save(device);
        log.info("Device {} ({}) {}", device.getId(), device.getHostname(), isNew ? "registered" : "re-registered");

        return new RegisterResponse(device.getId(), controllerIdentityService.controllerId(), now,
                agentDefaults.heartbeatIntervalSeconds(), true);
    }

    @Transactional
    public HeartbeatResponse heartbeat(UUID deviceId, HeartbeatRequest request, String sourceIp) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND",
                        "No device registered with id " + deviceId));

        Instant now = Instant.now();
        device.setStatus(DeviceStatus.ONLINE);
        device.setLastHeartbeatAt(request.occurredAt());
        device.setServiceState(request.serviceState());
        device.setSessionState(request.sessionState());
        device.setCurrentUsername(request.currentUser());
        device.setIdleSeconds(request.idleSeconds());
        device.setSourceIp(sourceIp);
        if (StringUtils.hasText(request.agentVersion())) {
            device.setAgentVersion(request.agentVersion());
        }
        device.setUpdatedAt(now);
        deviceRepository.save(device);

        // Idempotent on eventId like everything else the agent submits, so a
        // retried heartbeat after a dropped response doesn't double-write.
        if (!heartbeatRepository.existsById(request.eventId())) {
            DeviceHeartbeat heartbeat = new DeviceHeartbeat(request.eventId());
            heartbeat.setDeviceId(deviceId);
            heartbeat.setOccurredAt(request.occurredAt());
            heartbeat.setReceivedAt(now);
            heartbeat.setUptimeSeconds(request.uptimeSeconds());
            heartbeat.setServiceState(request.serviceState());
            heartbeat.setSessionState(request.sessionState());
            heartbeat.setCurrentUsername(request.currentUser());
            heartbeat.setIdleSeconds(request.idleSeconds());
            heartbeat.setSourceIp(sourceIp);
            heartbeatRepository.save(heartbeat);
        }

        return new HeartbeatResponse(true, now, agentDefaults.heartbeatIntervalSeconds());
    }

    public Device requireDevice(UUID deviceId) {
        return deviceRepository.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "No device registered with id " + deviceId));
    }

    public Page<Device> list(DeviceStatus status, String hostname, Pageable pageable) {
        boolean hasStatus = status != null;
        boolean hasHostname = StringUtils.hasText(hostname);
        if (hasStatus && hasHostname) {
            return deviceRepository.findByStatusAndHostnameContainingIgnoreCase(status, hostname, pageable);
        }
        if (hasStatus) {
            return deviceRepository.findByStatus(status, pageable);
        }
        if (hasHostname) {
            return deviceRepository.findByHostnameContainingIgnoreCase(hostname, pageable);
        }
        return deviceRepository.findAll(pageable);
    }

    public long countByStatus(DeviceStatus status) {
        return deviceRepository.countByStatus(status);
    }

    /**
     * Flips devices marked ONLINE to OFFLINE once their last signal of life
     * is older than max(heartbeatInterval x 3, offlineMinimumSeconds).
     */
    @Transactional
    public void recomputeOnlineStatus() {
        long thresholdSeconds = Math.max(
                (long) agentDefaults.heartbeatIntervalSeconds() * 3,
                deviceProperties.offlineMinimumSeconds());
        Instant cutoff = Instant.now().minusSeconds(thresholdSeconds);
        List<Device> stale = deviceRepository.findStaleOnlineDevices(cutoff);
        for (Device device : stale) {
            device.setStatus(DeviceStatus.OFFLINE);
            device.setUpdatedAt(Instant.now());
        }
        if (!stale.isEmpty()) {
            deviceRepository.saveAll(stale);
            log.debug("Marked {} device(s) offline (no signal for {}s)", stale.size(), thresholdSeconds);
        }
    }
}
