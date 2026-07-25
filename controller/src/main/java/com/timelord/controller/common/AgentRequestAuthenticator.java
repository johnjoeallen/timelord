package com.timelord.controller.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Authenticates an inbound agent request. Phase 1 ships only
 * {@link NoOpAgentRequestAuthenticator}; a later security phase adds a real
 * implementation (Ed25519-signed requests, mTLS, ...) behind this same
 * interface so REST controllers don't need to change.
 */
public interface AgentRequestAuthenticator {
    AuthenticatedAgent authenticate(HttpServletRequest request);
}
