package com.timelord.controller.common;

import java.util.UUID;

/**
 * Result of {@link AgentRequestAuthenticator#authenticate}. In Phase 1 this
 * carries no real identity guarantee — {@link #deviceId()} is {@code null}
 * and {@link #anonymous()} is always {@code true} — but request handlers are
 * written against this type now so a later signed/mTLS authenticator can be
 * dropped in without changing business logic.
 */
public record AuthenticatedAgent(UUID deviceId, boolean anonymous) {

    public static AuthenticatedAgent unauthenticated() {
        return new AuthenticatedAgent(null, true);
    }
}
