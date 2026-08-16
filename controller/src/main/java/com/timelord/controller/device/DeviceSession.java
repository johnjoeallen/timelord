package com.timelord.controller.device;

import java.time.Instant;
import java.util.UUID;

/**
 * A device's presence on the network from when it starts up until it either
 * shuts down explicitly, is suspended, or stops sending heartbeats for
 * longer than {@link DeviceSessionService#MAX_SESSION_GAP}. There's no
 * persisted session table — see {@link DeviceSessionService} for how these
 * are reconstructed from the event log.
 */
public record DeviceSession(
        UUID deviceId,
        String deviceName,
        String hostname,
        Instant start,
        Instant end,
        EndReason endReason,
        long durationSeconds
) {
    public enum EndReason {
        /** An AGENT_STOPPING event closed the session (graceful shutdown). */
        STOPPED,
        /** A SYSTEM_SUSPEND event closed the session (the device went to sleep). */
        SUSPENDED,
        /** No more heartbeats arrived for longer than the session gap threshold. */
        DISAPPEARED
    }

    public boolean isOngoing() {
        return end == null;
    }

    /** Compact human-readable duration, e.g. "2d 4h", "3h 12m", "45m", "<1m". */
    public String durationLabel() {
        long days = durationSeconds / 86_400;
        long hours = (durationSeconds % 86_400) / 3_600;
        long minutes = (durationSeconds % 3_600) / 60;

        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m";
        }
        return "<1m";
    }
}
