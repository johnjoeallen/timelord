package com.timelord.controller.device;

import com.timelord.controller.event.DeviceEvent;
import com.timelord.controller.event.DeviceEventRepository;
import com.timelord.controller.event.EventType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Derives per-device sessions — from when a device is first seen until it
 * either shuts down explicitly or stops sending heartbeats — from the
 * device_event history, rather than a separately maintained session table.
 * At Phase 1 scale, reconstructing on read from AGENT_STARTED /
 * AGENT_STOPPING / HEARTBEAT_SENT events is cheap and avoids a second
 * source of truth to keep in sync with the event log.
 */
@Service
public class DeviceSessionService {

    private static final List<EventType> SESSION_BOUNDARY_TYPES =
            List.of(EventType.AGENT_STARTED, EventType.AGENT_STOPPING, EventType.HEARTBEAT_SENT);

    private final DeviceEventRepository eventRepository;

    public DeviceSessionService(DeviceEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    /** Newest session first. */
    public List<DeviceSession> sessionsFor(Device device) {
        List<DeviceEvent> events =
                eventRepository.findByDeviceIdAndEventTypeInOrderByOccurredAtAsc(device.getId(), SESSION_BOUNDARY_TYPES);

        List<DeviceSession> sessions = new ArrayList<>();
        Instant sessionStart = null;
        Instant lastSeenAt = null;

        for (DeviceEvent event : events) {
            if (event.getEventType() == EventType.AGENT_STARTED) {
                if (sessionStart != null) {
                    // No AGENT_STOPPING arrived before the next start — the
                    // previous run ended by disappearing, not shutting down.
                    sessions.add(closed(device, sessionStart, lastSeenAt, DeviceSession.EndReason.DISAPPEARED));
                }
                sessionStart = event.getOccurredAt();
                lastSeenAt = event.getOccurredAt();
            } else if (event.getEventType() == EventType.AGENT_STOPPING) {
                if (sessionStart != null) {
                    sessions.add(closed(device, sessionStart, event.getOccurredAt(), DeviceSession.EndReason.STOPPED));
                    sessionStart = null;
                    lastSeenAt = null;
                }
            } else {
                lastSeenAt = event.getOccurredAt();
            }
        }

        if (sessionStart != null) {
            if (device.getStatus() == DeviceStatus.ONLINE) {
                sessions.add(open(device, sessionStart));
            } else {
                Instant end = lastSeenAt != null ? lastSeenAt : sessionStart;
                sessions.add(closed(device, sessionStart, end, DeviceSession.EndReason.DISAPPEARED));
            }
        }

        if (sessions.isEmpty() && device.getRegisteredAt() != null) {
            // No AGENT_STARTED on record (e.g. a device registered before
            // session tracking existed) — fall back to registration time so
            // the device still shows up with a session.
            Instant start = device.getRegisteredAt();
            if (device.getStatus() == DeviceStatus.ONLINE) {
                sessions.add(open(device, start));
            } else {
                Instant end = device.getLastHeartbeatAt() != null ? device.getLastHeartbeatAt() : start;
                sessions.add(closed(device, start, end, DeviceSession.EndReason.DISAPPEARED));
            }
        }

        Collections.reverse(sessions);
        return sessions;
    }

    /** Newest session first, across every given device, capped at `limit` total. */
    public List<DeviceSession> recentSessions(List<Device> devices, int limit) {
        List<DeviceSession> all = new ArrayList<>();
        for (Device device : devices) {
            all.addAll(sessionsFor(device));
        }
        all.sort(Comparator.comparing(DeviceSession::start).reversed());
        return all.size() > limit ? all.subList(0, limit) : all;
    }

    private DeviceSession closed(Device device, Instant start, Instant end, DeviceSession.EndReason reason) {
        long duration = Math.max(0, end.getEpochSecond() - start.getEpochSecond());
        return new DeviceSession(device.getId(), device.getDeviceName(), device.getHostname(), start, end, reason, duration);
    }

    private DeviceSession open(Device device, Instant start) {
        long duration = Math.max(0, Instant.now().getEpochSecond() - start.getEpochSecond());
        return new DeviceSession(device.getId(), device.getDeviceName(), device.getHostname(), start, null, null, duration);
    }
}
