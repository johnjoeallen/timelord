package com.timelord.controller.device;

import java.time.Instant;

/**
 * What the device's power override amounts to right now, for display. {@code at} is the relevant time
 * for the state (see {@link State}); {@code thenOff} says what a {@link State#HOLD} turns into.
 */
public record PowerStatus(State state, Instant at, boolean thenOff) {

    public enum State {
        /** No override: following the schedule. */
        SCHEDULE,
        /** Off has been requested and begins at {@code at} (a grace period). */
        OFF_PENDING,
        /** Forced off since {@code at}. */
        OFF,
        /** Schedule ignored until {@code at}, then off if {@code thenOff}, otherwise back to the schedule. */
        HOLD,
        /** A hold that ended at {@code at} and left the device off. */
        HOLD_ENDED_OFF
    }

    public static PowerStatus of(Device device, Instant now) {
        if (device.getPowerMode() == PowerMode.OFF && device.getOffFrom() != null) {
            return new PowerStatus(device.getOffFrom().isAfter(now) ? State.OFF_PENDING : State.OFF,
                    device.getOffFrom(), false);
        }
        Instant until = device.getHoldUntil();
        if (until != null) {
            if (until.isAfter(now)) {
                return new PowerStatus(State.HOLD, until, device.isHoldThenOff());
            }
            if (device.isHoldThenOff()) {
                return new PowerStatus(State.HOLD_ENDED_OFF, until, true);
            }
        }
        return new PowerStatus(State.SCHEDULE, null, false);
    }
}
