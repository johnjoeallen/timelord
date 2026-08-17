package com.timelord.controller.device;

import com.timelord.controller.event.DeviceEvent;
import com.timelord.controller.event.DeviceEventRepository;
import com.timelord.controller.event.EventType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Derives per-device *login* sessions — from a USER_LOGON until the user
 * logs off, the device suspends, the agent stops, or it stops sending
 * heartbeats for too long — from the device_event history, rather than a
 * separately maintained session table. A device simply being online
 * (AGENT_STARTED, heartbeats) with nobody logged in has no session at all;
 * see {@link Device#isActive()} for that live "is someone logged in right
 * now" signal shown elsewhere on the dashboard. At Phase 1 scale,
 * reconstructing on read is cheap and avoids a second source of truth to
 * keep in sync with the event log.
 */
@Service
public class DeviceSessionService {

    /**
     * A silence longer than this — between any two consecutive session
     * events, or between the last one and now — ends the session even
     * without an explicit logoff/stop/suspend. Only a fresh USER_LOGON
     * starts a new one; a heartbeat alone never resurrects or restarts a
     * session across the gap, since it says nothing about whether anyone
     * is actually logged in.
     */
    static final Duration MAX_SESSION_GAP = Duration.ofMinutes(3);

    private static final List<EventType> SESSION_BOUNDARY_TYPES = List.of(
            EventType.USER_LOGON, EventType.USER_LOGOFF,
            EventType.AGENT_STOPPING, EventType.SYSTEM_SUSPEND,
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
                case USER_LOGON -> {
                    if (sessionStart != null) {
                        // Shouldn't normally happen (a logon while one is
                        // already open implies a missed logoff) — close the
                        // earlier one defensively rather than losing it.
                        sessions.add(closed(device, sessionStart, lastSeenAt, DeviceSession.EndReason.DISAPPEARED));
                    }
                    sessionStart = event.getOccurredAt();
                    lastSeenAt = event.getOccurredAt();
                }
                case USER_LOGOFF -> {
                    // An explicit logoff always closes at its own timestamp,
                    // regardless of how long it's been since the last
                    // heartbeat — it's authoritative, not a guess from silence.
                    if (sessionStart != null) {
                        sessions.add(closed(device, sessionStart, event.getOccurredAt(), DeviceSession.EndReason.LOGGED_OUT));
                        sessionStart = null;
                        lastSeenAt = null;
                    }
                }
                case AGENT_STOPPING -> {
                    if (sessionStart != null) {
                        sessions.add(closed(device, sessionStart, event.getOccurredAt(), DeviceSession.EndReason.STOPPED));
                        sessionStart = null;
                        lastSeenAt = null;
                    }
                }
                case SYSTEM_SUSPEND -> {
                    if (sessionStart != null) {
                        sessions.add(closed(device, sessionStart, event.getOccurredAt(), DeviceSession.EndReason.SUSPENDED));
                        sessionStart = null;
                        lastSeenAt = null;
                    }
                }
                default -> {
                    // HEARTBEAT_SENT: a "still alive" signal for whatever
                    // session is already open. Never starts one on its own —
                    // a heartbeat says nothing about whether a user is
                    // logged in, only USER_LOGON does that. A silence
                    // longer than the gap threshold before one arrives means
                    // the session actually ended partway through the gap.
                    if (sessionStart != null) {
                        if (lastSeenAt != null && exceedsGap(lastSeenAt, event.getOccurredAt())) {
                            sessions.add(closed(device, sessionStart, lastSeenAt, DeviceSession.EndReason.DISAPPEARED));
                            sessionStart = null;
                        } else {
                            lastSeenAt = event.getOccurredAt();
                        }
                    }
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

        Collections.reverse(sessions);
        return sessions;
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
