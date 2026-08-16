package com.timelord.controller.device;

import com.timelord.controller.event.DeviceEvent;
import com.timelord.controller.event.DeviceEventRepository;
import com.timelord.controller.event.EventType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Derives per-device sessions — from when a device is first seen until it
 * either shuts down explicitly, is suspended, or stops sending heartbeats
 * for too long — from the device_event history, rather than a separately
 * maintained session table. At Phase 1 scale, reconstructing on read from
 * AGENT_STARTED / AGENT_STOPPING / SYSTEM_SUSPEND / SYSTEM_RESUME /
 * HEARTBEAT_SENT events is cheap and avoids a second source of truth to
 * keep in sync with the event log.
 */
@Service
public class DeviceSessionService {

    /**
     * A silence longer than this — between any two consecutive session
     * events, or between the last one and now — ends the session even
     * without an explicit stop/suspend, and a later heartbeat starts a new
     * one rather than resurrecting the old session across the gap.
     */
    static final Duration MAX_SESSION_GAP = Duration.ofMinutes(3);

    private static final List<EventType> SESSION_BOUNDARY_TYPES = List.of(
            EventType.AGENT_STARTED, EventType.AGENT_STOPPING,
            EventType.SYSTEM_SUSPEND, EventType.SYSTEM_RESUME,
            EventType.HEARTBEAT_SENT);

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
            switch (event.getEventType()) {
                case AGENT_STARTED -> {
                    if (sessionStart != null) {
                        // No AGENT_STOPPING/SUSPEND arrived before this restart
                        // — the previous run ended by disappearing.
                        sessions.add(closed(device, sessionStart, lastSeenAt, DeviceSession.EndReason.DISAPPEARED));
                    }
                    sessionStart = event.getOccurredAt();
                    lastSeenAt = event.getOccurredAt();
                }
                case AGENT_STOPPING -> {
                    // An explicit stop always closes at its own timestamp,
                    // regardless of how long it's been since the last
                    // heartbeat — it's authoritative, not a guess from silence.
                    if (sessionStart != null) {
                        sessions.add(closed(device, sessionStart, event.getOccurredAt(), DeviceSession.EndReason.STOPPED));
                        sessionStart = null;
                        lastSeenAt = null;
                    }
                }
                case SYSTEM_SUSPEND -> {
                    // Same reasoning as AGENT_STOPPING: explicit and authoritative.
                    if (sessionStart != null) {
                        sessions.add(closed(device, sessionStart, event.getOccurredAt(), DeviceSession.EndReason.SUSPENDED));
                        sessionStart = null;
                        lastSeenAt = null;
                    }
                }
                default -> {
                    // SYSTEM_RESUME or HEARTBEAT_SENT: both are "still alive"
                    // signals rather than explicit boundaries, so a silence
                    // longer than the gap threshold before one of these means
                    // the session actually ended partway through the gap —
                    // close it there and let this event start a new one,
                    // instead of resurrecting the old session across the gap.
                    if (sessionStart != null && lastSeenAt != null && exceedsGap(lastSeenAt, event.getOccurredAt())) {
                        sessions.add(closed(device, sessionStart, lastSeenAt, DeviceSession.EndReason.DISAPPEARED));
                        sessionStart = null;
                    }
                    if (sessionStart == null) {
                        sessionStart = event.getOccurredAt();
                    }
                    lastSeenAt = event.getOccurredAt();
                }
            }
        }

        if (sessionStart != null) {
            boolean stillFresh = lastSeenAt != null && !exceedsGap(lastSeenAt, Instant.now());
            if (device.getStatus() == DeviceStatus.ONLINE && stillFresh) {
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
            boolean stillFresh = device.getLastHeartbeatAt() != null && !exceedsGap(device.getLastHeartbeatAt(), Instant.now());
            if (device.getStatus() == DeviceStatus.ONLINE && stillFresh) {
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

    private static boolean exceedsGap(Instant from, Instant to) {
        return Duration.between(from, to).compareTo(MAX_SESSION_GAP) > 0;
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
