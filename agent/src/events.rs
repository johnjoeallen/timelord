//! Bridges the platform-independent usage tracker
//! ([`crate::tracker::SessionEvent`]) and agent lifecycle/networking
//! moments into [`QueuedEvent`]s ready for [`crate::queue::EventQueue`].
//!
//! `EventItem.sessionId` in the wire protocol refers to a *TimeLord* usage
//! session (a `device_session` row), which Phase 1's controller schema
//! doesn't have yet — so it's always `None` here. The raw Windows numeric
//! session ID goes into `data` instead, where it's still visible in the UI.

use std::collections::HashMap;

use chrono::Utc;
use serde_json::json;
use uuid::Uuid;

use crate::protocol::{EventSeverity, EventSource, EventType};
use crate::queue::QueuedEvent;
use crate::tracker::SessionEvent;

fn new_event(event_type: EventType, severity: EventSeverity, data: HashMap<String, serde_json::Value>) -> QueuedEvent {
    QueuedEvent {
        event_id: Uuid::new_v4(),
        event_type,
        occurred_at: Utc::now(),
        severity,
        source: EventSource::Agent,
        session_id: None,
        username: None,
        data,
    }
}

fn data(pairs: impl IntoIterator<Item = (&'static str, serde_json::Value)>) -> HashMap<String, serde_json::Value> {
    pairs.into_iter().map(|(k, v)| (k.to_string(), v)).collect()
}

/// Maps every tracker notification to its corresponding protocol event —
/// see design brief section 8 for the full vocabulary.
pub fn from_session_event(event: SessionEvent) -> QueuedEvent {
    let (event_type, windows_session_id) = match event {
        SessionEvent::Logon(id) => (EventType::UserLogon, Some(id)),
        SessionEvent::Logoff(id) => (EventType::UserLogoff, Some(id)),
        SessionEvent::Lock(id) => (EventType::UserLock, Some(id)),
        SessionEvent::Unlock(id) => (EventType::UserUnlock, Some(id)),
        SessionEvent::ConsoleConnect(id) => (EventType::SessionConnected, Some(id)),
        SessionEvent::ConsoleDisconnect(id) => (EventType::SessionDisconnected, Some(id)),
        SessionEvent::RemoteConnect(id) => (EventType::SessionConnected, Some(id)),
        SessionEvent::RemoteDisconnect(id) => (EventType::SessionDisconnected, Some(id)),
        SessionEvent::Suspend => (EventType::SystemSuspend, None),
        SessionEvent::Resume => (EventType::SystemResume, None),
    };
    let event_data = match windows_session_id {
        Some(id) => data([("windowsSessionId", json!(id))]),
        None => HashMap::new(),
    };
    new_event(event_type, EventSeverity::Info, event_data)
}

pub fn agent_started(agent_version: &str, start_reason: &str) -> QueuedEvent {
    new_event(
        EventType::AgentStarted,
        EventSeverity::Info,
        data([("agentVersion", json!(agent_version)), ("startReason", json!(start_reason))]),
    )
}

pub fn agent_stopping(agent_version: &str) -> QueuedEvent {
    new_event(EventType::AgentStopping, EventSeverity::Info, data([("agentVersion", json!(agent_version))]))
}

pub fn agent_error(message: &str) -> QueuedEvent {
    new_event(EventType::AgentError, EventSeverity::Error, data([("message", json!(message))]))
}

pub fn controller_discovery_started() -> QueuedEvent {
    new_event(EventType::ControllerDiscoveryStarted, EventSeverity::Info, HashMap::new())
}

pub fn controller_discovered(controller_id: Uuid, controller_name: &str, controller_url: &str) -> QueuedEvent {
    new_event(
        EventType::ControllerDiscovered,
        EventSeverity::Info,
        data([
            ("controllerId", json!(controller_id)),
            ("controllerName", json!(controller_name)),
            ("controllerUrl", json!(controller_url)),
        ]),
    )
}

pub fn controller_discovery_failed(reason: &str) -> QueuedEvent {
    new_event(EventType::ControllerDiscoveryFailed, EventSeverity::Warning, data([("reason", json!(reason))]))
}

pub fn controller_connected(controller_url: &str) -> QueuedEvent {
    new_event(EventType::ControllerConnected, EventSeverity::Info, data([("controllerUrl", json!(controller_url))]))
}

pub fn controller_disconnected(reason: &str) -> QueuedEvent {
    new_event(EventType::ControllerDisconnected, EventSeverity::Warning, data([("reason", json!(reason))]))
}

pub fn registration_succeeded(device_id: Uuid) -> QueuedEvent {
    new_event(EventType::RegistrationSucceeded, EventSeverity::Info, data([("deviceId", json!(device_id))]))
}

pub fn registration_failed(reason: &str) -> QueuedEvent {
    new_event(EventType::RegistrationFailed, EventSeverity::Error, data([("reason", json!(reason))]))
}

pub fn heartbeat_sent() -> QueuedEvent {
    new_event(EventType::HeartbeatSent, EventSeverity::Info, HashMap::new())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn logon_maps_to_user_logon_with_windows_session_id_in_data() {
        let event = from_session_event(SessionEvent::Logon(1));
        assert_eq!(event.event_type, EventType::UserLogon);
        assert_eq!(event.data.get("windowsSessionId"), Some(&json!(1)));
        assert_eq!(event.session_id, None, "Phase 1 has no device_session concept yet");
    }

    #[test]
    fn lock_maps_to_user_lock() {
        assert_eq!(from_session_event(SessionEvent::Lock(1)).event_type, EventType::UserLock);
    }

    #[test]
    fn suspend_and_resume_map_to_system_events_without_a_session_id() {
        let suspend = from_session_event(SessionEvent::Suspend);
        assert_eq!(suspend.event_type, EventType::SystemSuspend);
        assert!(suspend.data.is_empty());

        let resume = from_session_event(SessionEvent::Resume);
        assert_eq!(resume.event_type, EventType::SystemResume);
    }

    #[test]
    fn remote_and_console_connect_both_map_to_session_connected() {
        assert_eq!(from_session_event(SessionEvent::ConsoleConnect(1)).event_type, EventType::SessionConnected);
        assert_eq!(from_session_event(SessionEvent::RemoteConnect(1)).event_type, EventType::SessionConnected);
    }

    #[test]
    fn agent_started_carries_version_and_reason() {
        let event = agent_started("0.1.0", "WINDOWS_SERVICE_START");
        assert_eq!(event.event_type, EventType::AgentStarted);
        assert_eq!(event.data.get("agentVersion"), Some(&json!("0.1.0")));
        assert_eq!(event.data.get("startReason"), Some(&json!("WINDOWS_SERVICE_START")));
    }

    #[test]
    fn agent_error_is_error_severity() {
        let event = agent_error("connection refused");
        assert_eq!(event.severity, EventSeverity::Error);
    }
}
