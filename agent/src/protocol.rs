//! Wire types for the unauthenticated Phase-1 HTTP API
//! (`/api/v1/agents/...` on the controller). Field names and enum values are
//! chosen to serialize byte-for-byte compatible with the controller's Java
//! DTOs (`com.timelord.controller.agent.*`, `com.timelord.controller.event.*`).

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;

/// Full Phase-1 event vocabulary (design brief section 8). The controller
/// accepts all of these; the agent only emits a subset today — see
/// `events.rs` for what's actually wired up to the tracker.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum EventType {
    AgentStarted,
    AgentStopping,
    AgentError,
    ControllerDiscoveryStarted,
    ControllerDiscovered,
    ControllerDiscoveryFailed,
    ControllerConnected,
    ControllerDisconnected,
    RegistrationSucceeded,
    RegistrationFailed,
    HeartbeatSent,
    UserLogon,
    UserLogoff,
    UserLock,
    UserUnlock,
    SessionConnected,
    SessionDisconnected,
    SystemBoot,
    SystemShutdown,
    SystemSuspend,
    SystemResume,
    SystemTimeChanged,
    TimeZoneChanged,
    IdleStarted,
    IdleEnded,
    SupervisoryWarning,
    PolicyViolation,
    PowerActionRequested,
    PowerActionCompleted,
    PowerActionFailed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum EventSeverity {
    Info,
    Warning,
    Error,
    Critical,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum EventSource {
    Agent,
    Controller,
}

/// One network adapter as reported by the OS, regardless of whether it has
/// a routable address right now (unlike `RegisterRequest.local_ip_addresses`,
/// which is filtered down to routable IPs for the summary view — see
/// `agent::local_ip_addresses`). Lets the dashboard show every adapter the
/// device has, not just the ones currently carrying traffic.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NetworkInterfaceInfo {
    pub name: String,
    pub mac_address: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RegisterRequest {
    pub device_id: Uuid,
    pub device_name: String,
    pub hostname: String,
    pub agent_version: String,
    pub operating_system: String,
    pub operating_system_version: String,
    pub architecture: String,
    pub local_ip_addresses: Vec<String>,
    pub network_interfaces: Vec<NetworkInterfaceInfo>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RegisterResponse {
    pub device_id: Uuid,
    pub controller_id: Uuid,
    pub registered_at: DateTime<Utc>,
    pub heartbeat_interval_seconds: u32,
    pub accepted: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HeartbeatRequest {
    pub event_id: Uuid,
    pub occurred_at: DateTime<Utc>,
    pub agent_version: String,
    pub uptime_seconds: u64,
    pub service_state: String,
    pub current_user: Option<String>,
    pub session_state: String,
    pub idle_seconds: Option<u64>,
    pub controller_url: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HeartbeatResponse {
    pub accepted: bool,
    pub server_time: DateTime<Utc>,
    pub heartbeat_interval_seconds: u32,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EventItem {
    pub event_id: Uuid,
    pub event_type: EventType,
    pub occurred_at: DateTime<Utc>,
    pub severity: EventSeverity,
    pub source: EventSource,
    pub session_id: Option<Uuid>,
    pub username: Option<String>,
    pub data: HashMap<String, serde_json::Value>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EventSubmissionRequest {
    pub events: Vec<EventItem>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EventSubmissionResponse {
    pub accepted: u32,
    pub duplicates: u32,
    pub rejected: u32,
    pub results: Vec<EventResult>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EventResult {
    pub event_id: Uuid,
    pub status: EventResultStatus,
    pub reason: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum EventResultStatus {
    Accepted,
    Duplicate,
    Rejected,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SystemInfoResponse {
    pub controller_id: Uuid,
    pub controller_name: String,
    pub protocol_version: u32,
    pub controller_version: String,
    pub server_time: DateTime<Utc>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn event_type_serializes_as_screaming_snake_case() {
        assert_eq!(serde_json::to_string(&EventType::AgentStarted).unwrap(), "\"AGENT_STARTED\"");
        assert_eq!(serde_json::to_string(&EventType::UserLogon).unwrap(), "\"USER_LOGON\"");
        assert_eq!(
            serde_json::to_string(&EventType::ControllerDiscoveryFailed).unwrap(),
            "\"CONTROLLER_DISCOVERY_FAILED\""
        );
    }

    #[test]
    fn register_request_uses_camel_case_fields() {
        let req = RegisterRequest {
            device_id: Uuid::nil(),
            device_name: "PC".into(),
            hostname: "pc".into(),
            agent_version: "0.1.0".into(),
            operating_system: "Windows 11".into(),
            operating_system_version: "10.0".into(),
            architecture: "x86_64".into(),
            local_ip_addresses: vec!["10.0.0.1".into()],
            network_interfaces: vec![NetworkInterfaceInfo { name: "Ethernet".into(), mac_address: Some("aa:bb:cc:dd:ee:ff".into()) }],
        };
        let json = serde_json::to_value(&req).unwrap();
        assert!(json.get("deviceId").is_some());
        assert!(json.get("operatingSystemVersion").is_some());
        assert!(json.get("localIpAddresses").is_some());
        assert_eq!(json["networkInterfaces"][0]["macAddress"], "aa:bb:cc:dd:ee:ff");
    }

    #[test]
    fn register_response_deserializes_from_controller_shaped_json() {
        let json = r#"{
            "deviceId": "764f814d-f71c-41d4-b89f-d4f914653036",
            "controllerId": "5872d46f-8c22-411f-9184-6ec069b85370",
            "registeredAt": "2026-07-25T12:00:00Z",
            "heartbeatIntervalSeconds": 30,
            "accepted": true
        }"#;
        let resp: RegisterResponse = serde_json::from_str(json).unwrap();
        assert_eq!(resp.heartbeat_interval_seconds, 30);
        assert!(resp.accepted);
    }

    #[test]
    fn event_result_status_deserializes() {
        let json = r#""DUPLICATE""#;
        let status: EventResultStatus = serde_json::from_str(json).unwrap();
        assert_eq!(status, EventResultStatus::Duplicate);
    }
}
