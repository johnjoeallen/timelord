package com.timelord.controller.event;

/**
 * Full Phase-1 event vocabulary (design brief section 8). Not every value is
 * emitted by the agent yet — see agent/src/events.rs for what's actually
 * wired up — but the controller accepts and stores all of them so new agent
 * event types don't require a migration to add.
 */
public enum EventType {
    AGENT_STARTED,
    AGENT_STOPPING,
    AGENT_ERROR,
    CONTROLLER_DISCOVERY_STARTED,
    CONTROLLER_DISCOVERED,
    CONTROLLER_DISCOVERY_FAILED,
    CONTROLLER_CONNECTED,
    CONTROLLER_DISCONNECTED,
    REGISTRATION_SUCCEEDED,
    REGISTRATION_FAILED,
    HEARTBEAT_SENT,
    USER_LOGON,
    USER_LOGOFF,
    USER_LOCK,
    USER_UNLOCK,
    SESSION_CONNECTED,
    SESSION_DISCONNECTED,
    SYSTEM_BOOT,
    SYSTEM_SHUTDOWN,
    SYSTEM_SUSPEND,
    SYSTEM_RESUME,
    SYSTEM_TIME_CHANGED,
    TIME_ZONE_CHANGED,
    IDLE_STARTED,
    IDLE_ENDED,
    SUPERVISORY_WARNING,
    POLICY_VIOLATION,
    POWER_ACTION_REQUESTED,
    POWER_ACTION_COMPLETED,
    POWER_ACTION_FAILED
}
