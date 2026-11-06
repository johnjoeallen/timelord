package com.timelord.controller.device;

import com.timelord.controller.agent.AgentSchedule;
import com.timelord.controller.agent.HeartbeatRequest;
import com.timelord.controller.agent.HeartbeatResponse;
import com.timelord.controller.agent.RegisterRequest;
import com.timelord.controller.agent.RegisterResponse;
import com.timelord.controller.common.ApiException;
import com.timelord.controller.schedule.ScheduleService;
import com.timelord.controller.schedule.OffWindow;
import com.timelord.controller.config.AgentDefaultsProperties;
import com.timelord.controller.config.DeviceProperties;
import com.timelord.controller.discovery.ControllerIdentityService;
import com.timelord.controller.event.DeviceEventRepository;
import com.timelord.controller.event.DeviceHeartbeat;
import com.timelord.controller.event.DeviceHeartbeatRepository;
import com.timelord.controller.ws.DeviceUpdatesWebSocketHandler;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
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
    private final DeviceUpdatesWebSocketHandler deviceUpdates;
    private final ScheduleService scheduleService;
    private final DeviceEventRepository eventRepository;

    public DeviceService(DeviceRepository deviceRepository,
                          DeviceHeartbeatRepository heartbeatRepository,
                          ControllerIdentityService controllerIdentityService,
                          AgentDefaultsProperties agentDefaults,
                          DeviceProperties deviceProperties,
                          DeviceUpdatesWebSocketHandler deviceUpdates,
                          ScheduleService scheduleService,
                          DeviceEventRepository eventRepository) {
        this.deviceRepository = deviceRepository;
        this.heartbeatRepository = heartbeatRepository;
        this.controllerIdentityService = controllerIdentityService;
        this.agentDefaults = agentDefaults;
        this.deviceProperties = deviceProperties;
        this.deviceUpdates = deviceUpdates;
        this.scheduleService = scheduleService;
        this.eventRepository = eventRepository;
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
        device.setNetworkInterfaces(request.networkInterfaces());
        device.setSourceIp(sourceIp);
        device.setStatus(DeviceStatus.ONLINE);
        device.setLastRegistrationAt(now);
        device.setUpdatedAt(now);

        deviceRepository.save(device);
        log.info("Device {} ({}) {}", device.getId(), device.getHostname(), isNew ? "registered" : "re-registered");
        deviceUpdates.broadcastDeviceChanged(device, isNew ? "REGISTERED" : "RE_REGISTERED");

        return new RegisterResponse(device.getId(), controllerIdentityService.controllerId(), now,
                agentDefaults.heartbeatIntervalSeconds(), true,
                scheduleFor(device));
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
        deviceUpdates.broadcastDeviceChanged(device, "HEARTBEAT");

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

        return new HeartbeatResponse(true, now, agentDefaults.heartbeatIntervalSeconds(),
                scheduleFor(device));
    }

    /**
     * What the agent is told to enforce. The power override wins over the schedule: forced off (from
     * {@code offFrom}, possibly a little ahead as a grace period) is an endless off window; a hold that is
     * to end in off is an endless off window from the hold's end; a hold that returns to the schedule
     * works like an extension that starts now.
     */
    public AgentSchedule scheduleFor(Device device) {
        if (device.getPowerMode() == PowerMode.OFF && device.getOffFrom() != null) {
            return scheduleService.forcedOff(device.getScheduleId(), device.getOffFrom());
        }
        Instant hold = device.getHoldUntil();
        if (hold != null && device.isHoldThenOff()) {
            return scheduleService.forcedOff(device.getScheduleId(), hold);
        }
        Instant until = hold != null && hold.isAfter(Instant.now()) ? hold : device.getExtensionUntil();
        return scheduleService.scheduleFor(device.getScheduleId(), until);
    }

    /** Longest "on for a period" hold: a week. */
    public static final int MAX_HOLD_MINUTES = 7 * 24 * 60;
    /** Longest grace period before a forced off begins. */
    public static final int MAX_GRACE_MINUTES = 240;

    /** Back to following the schedule: cancels a forced off (pending or in force) and any hold. */
    @Transactional
    public Device powerOn(UUID deviceId) {
        Device device = requireDevice(deviceId);
        clearPower(device);
        return save(device);
    }

    /** Off no matter what, starting {@code graceMinutes} from now (0 is immediately). Clears any extension. */
    @Transactional
    public Device powerOff(UUID deviceId, int graceMinutes) {
        if (graceMinutes < 0 || graceMinutes > MAX_GRACE_MINUTES) {
            throw ApiException.badRequest("INVALID_GRACE",
                    "graceMinutes must be between 0 and " + MAX_GRACE_MINUTES);
        }
        Device device = requireDevice(deviceId);
        clearPower(device);
        device.setExtensionUntil(null);
        device.setPowerMode(PowerMode.OFF);
        device.setOffFrom(Instant.now().plus(Duration.ofMinutes(graceMinutes)));
        return save(device);
    }

    /**
     * On for {@code minutes} from now regardless of the schedule, then either back to the schedule or, if
     * {@code thenOff}, off until someone changes it. Clears any extension.
     */
    @Transactional
    public Device holdOn(UUID deviceId, int minutes, boolean thenOff) {
        if (minutes < 1 || minutes > MAX_HOLD_MINUTES) {
            throw ApiException.badRequest("INVALID_HOLD", "minutes must be between 1 and " + MAX_HOLD_MINUTES);
        }
        Device device = requireDevice(deviceId);
        clearPower(device);
        device.setExtensionUntil(null);
        device.setHoldUntil(Instant.now().plus(Duration.ofMinutes(minutes)));
        device.setHoldThenOff(thenOff);
        return save(device);
    }

    private static void clearPower(Device device) {
        device.setPowerMode(PowerMode.ON);
        device.setOffFrom(null);
        device.setHoldUntil(null);
        device.setHoldThenOff(false);
    }

    private Device save(Device device) {
        device.setUpdatedAt(Instant.now());
        Device saved = deviceRepository.save(device);
        log.info("Device {} ({}) power: {}", saved.getId(), saved.getHostname(), PowerStatus.of(saved, Instant.now()));
        return saved;
    }

    /**
     * Lets the device stay on {@code minutes} longer than its schedule says: if it is currently inside an
     * off window it is allowed back on for that long, otherwise the next off window starts that much later.
     * The schedule itself is untouched.
     */
    @Transactional
    public Device extend(UUID deviceId, int minutes) {
        Device device = requireDevice(deviceId);
        PowerStatus.State power = PowerStatus.of(device, Instant.now()).state();
        if (power != PowerStatus.State.SCHEDULE) {
            throw new ApiException(HttpStatus.CONFLICT, "POWER_OVERRIDE",
                    "Device is under a power override; switch it back to On before extending");
        }
        if (device.getScheduleId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_SCHEDULE", "Device has no schedule assigned");
        }
        Instant now = Instant.now();
        OffWindow next = scheduleService.effectiveWindows(device.getScheduleId(), device.getExtensionUntil(), Duration.ofDays(2))
                .stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "NO_OFF_WINDOW", "No upcoming off window to extend"));
        Instant base = next.offAt().isBefore(now) ? now : next.offAt();
        device.setExtensionUntil(base.plus(Duration.ofMinutes(minutes)));
        device.setUpdatedAt(now);
        return deviceRepository.save(device);
    }

    /**
     * Removes the device and all of its events and heartbeats. A still-running agent will simply
     * re-register (as a new row) on its next heartbeat, so uninstall or stop the agent first.
     */
    @Transactional
    public void delete(UUID deviceId) {
        Device device = requireDevice(deviceId);
        int events = eventRepository.deleteAllByDeviceId(deviceId);
        int heartbeats = heartbeatRepository.deleteAllByDeviceId(deviceId);
        deviceRepository.delete(device);
        deviceRepository.flush();
        log.info("Device {} ({}) deleted along with {} event(s) and {} heartbeat(s)",
                deviceId, device.getHostname(), events, heartbeats);
        deviceUpdates.broadcastDeviceChanged(device, "DELETED");
    }

    @Transactional
    public Device clearExtension(UUID deviceId) {
        Device device = requireDevice(deviceId);
        device.setExtensionUntil(null);
        device.setUpdatedAt(Instant.now());
        return deviceRepository.save(device);
    }

    public Device requireDevice(UUID deviceId) {
        return deviceRepository.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "No device registered with id " + deviceId));
    }

    /** Assigns a schedule to the device, or clears it when {@code scheduleId} is null. */
    @Transactional
    public Device assignSchedule(UUID deviceId, UUID scheduleId) {
        Device device = requireDevice(deviceId);
        if (scheduleId != null) {
            scheduleService.require(scheduleId);
        }
        device.setScheduleId(scheduleId);
        device.setExtensionUntil(null);
        device.setUpdatedAt(Instant.now());
        return deviceRepository.save(device);
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
            for (Device device : stale) {
                deviceUpdates.broadcastDeviceChanged(device, "WENT_OFFLINE");
            }
        }
    }
}
