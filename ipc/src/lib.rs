//! Wire types for the local named pipe between the agent service and the
//! per-user notifier. One JSON object per line in each direction. The
//! service is a passive, read-only responder; all timing and toast logic
//! lives in the notifier.

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

/// Windows named pipe the agent serves and the notifier polls.
pub const PIPE_NAME: &str = r"\\.\pipe\timelord-notify";

/// How far ahead the service reports actions.
pub const HORIZON_SECONDS: i64 = 30 * 60;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum Request {
    GetPending,
    GetMessages,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum Response {
    #[serde(rename_all = "camelCase")]
    Pending { server_utc: DateTime<Utc>, actions: Vec<PendingAction> },
    #[serde(rename_all = "camelCase")]
    Messages { server_utc: DateTime<Utc>, messages: Vec<Message> },
}

/// A free-text message from an administrator, to show once to the logged-on user.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Message {
    pub id: Uuid,
    pub title: Option<String>,
    pub body: String,
    pub sent_utc: DateTime<Utc>,
    pub expires_utc: DateTime<Utc>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PendingAction {
    /// Stable for the same action at the same time; changes if the time moves
    /// (e.g. an extension), which the notifier treats as cancel + new action.
    pub id: Uuid,
    pub action: Action,
    pub at_utc: DateTime<Utc>,
    pub reason: Reason,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Action {
    Shutdown,
    Restart,
    Sleep,
    Logoff,
    Lock,
    BreakStart,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Reason {
    Schedule,
    DailyLimit,
    MandatoryBreak,
    RemoteCommand,
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeZone;

    #[test]
    fn request_is_a_tagged_object() {
        assert_eq!(serde_json::to_string(&Request::GetPending).unwrap(), r#"{"type":"GetPending"}"#);
        assert_eq!(serde_json::from_str::<Request>(r#"{"type":"GetPending"}"#).unwrap(), Request::GetPending);
    }

    #[test]
    fn messages_round_trip() {
        let at = Utc.with_ymd_and_hms(2026, 10, 26, 22, 30, 0).unwrap();
        let response = Response::Messages {
            server_utc: at,
            messages: vec![Message { id: Uuid::nil(), title: None, body: "hi".into(), sent_utc: at, expires_utc: at }],
        };
        let json = serde_json::to_string(&response).unwrap();
        assert_eq!(serde_json::from_str::<Response>(&json).unwrap(), response);
        assert_eq!(serde_json::from_str::<Request>(r#"{"type":"GetMessages"}"#).unwrap(), Request::GetMessages);
    }

    #[test]
    fn response_round_trips_with_camel_case_fields() {
        let at = Utc.with_ymd_and_hms(2026, 10, 26, 22, 30, 0).unwrap();
        let response = Response::Pending {
            server_utc: at,
            actions: vec![PendingAction { id: Uuid::nil(), action: Action::Sleep, at_utc: at, reason: Reason::Schedule }],
        };
        let json = serde_json::to_string(&response).unwrap();
        assert!(json.contains(r#""serverUtc""#) && json.contains(r#""atUtc""#), "{json}");
        assert_eq!(serde_json::from_str::<Response>(&json).unwrap(), response);
    }
}
