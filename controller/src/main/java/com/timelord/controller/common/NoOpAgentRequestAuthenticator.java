package com.timelord.controller.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** Phase 1: accepts every request without verifying identity. See {@link AgentRequestAuthenticator}. */
@Component
public class NoOpAgentRequestAuthenticator implements AgentRequestAuthenticator {

    @Override
    public AuthenticatedAgent authenticate(HttpServletRequest request) {
        return AuthenticatedAgent.unauthenticated();
    }
}
