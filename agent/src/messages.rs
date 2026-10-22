//! Administrator messages waiting to be shown to the user, cached on disk so
//! they survive restarts and controller outages. `last_seq` is the cursor
//! the controller polls from, so a message is never fetched twice.

use std::path::Path;

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

use crate::protocol::AgentMessage;

#[derive(Debug, Default, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MessageStore {
    pub last_seq: i64,
    pub messages: Vec<AgentMessage>,
}

impl MessageStore {
    /// Adds newly delivered messages, advances the cursor, and drops expired ones.
    /// Returns whether anything changed (so the caller knows to persist).
    pub fn apply(&mut self, incoming: Vec<AgentMessage>, now: DateTime<Utc>) -> bool {
        let before = self.clone();
        for message in incoming {
            self.last_seq = self.last_seq.max(message.seq);
            if !self.messages.iter().any(|m| m.id == message.id) {
                self.messages.push(message);
            }
        }
        self.messages.retain(|m| m.expires_at > now);
        *self != before
    }

    /// Messages still worth showing.
    pub fn live(&self, now: DateTime<Utc>) -> Vec<AgentMessage> {
        self.messages
            .iter()
            .filter(|m| m.expires_at > now)
            .cloned()
            .collect()
    }
}

pub fn load(path: &Path) -> MessageStore {
    let Ok(text) = std::fs::read_to_string(path) else {
        return MessageStore::default();
    };
    serde_json::from_str(&text).unwrap_or_else(|err| {
        tracing::warn!(?err, "ignoring unreadable cached messages");
        MessageStore::default()
    })
}

/// Written via a temp file + rename so a crash mid-write can't truncate the cache.
pub fn save(path: &Path, store: &MessageStore) -> std::io::Result<()> {
    let tmp = path.with_extension("json.tmp");
    std::fs::write(&tmp, serde_json::to_vec_pretty(store)?)?;
    std::fs::rename(tmp, path)
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::{Duration, TimeZone};
    use uuid::Uuid;

    fn now() -> DateTime<Utc> {
        Utc.with_ymd_and_hms(2026, 10, 26, 22, 0, 0).unwrap()
    }

    fn msg(seq: i64, expires_in_min: i64) -> AgentMessage {
        AgentMessage {
            seq,
            id: Uuid::from_u128(seq as u128),
            title: None,
            body: format!("m{seq}"),
            created_at: now(),
            expires_at: now() + Duration::minutes(expires_in_min),
        }
    }

    #[test]
    fn apply_advances_cursor_and_dedupes() {
        let mut s = MessageStore::default();
        assert!(s.apply(vec![msg(3, 60), msg(5, 60)], now()));
        assert_eq!(s.last_seq, 5);
        assert!(
            !s.apply(vec![msg(5, 60)], now()),
            "same message again changes nothing"
        );
        assert_eq!(s.messages.len(), 2);
    }

    #[test]
    fn expired_messages_are_dropped_but_the_cursor_keeps_its_place() {
        let mut s = MessageStore::default();
        s.apply(vec![msg(7, 10)], now());
        let later = now() + Duration::minutes(30);
        assert!(s.live(later).is_empty());
        assert!(s.apply(vec![], later));
        assert!(s.messages.is_empty());
        assert_eq!(s.last_seq, 7);
    }

    #[test]
    fn already_expired_messages_still_advance_the_cursor() {
        let mut s = MessageStore::default();
        s.apply(vec![msg(9, -5)], now());
        assert_eq!(s.last_seq, 9);
        assert!(s.messages.is_empty());
    }

    #[test]
    fn save_and_load_round_trip() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("messages.json");
        let mut s = MessageStore::default();
        s.apply(vec![msg(2, 60)], now());
        save(&path, &s).unwrap();
        assert_eq!(load(&path), s);
        assert_eq!(
            load(&dir.path().join("missing.json")),
            MessageStore::default()
        );
    }

    #[test]
    fn deserializes_controller_json() {
        let json = r#"[{"seq":4,"id":"00000000-0000-0000-0000-000000000004","title":"Hi","body":"Dinner",
            "createdAt":"2026-10-26T22:00:00Z","expiresAt":"2026-10-27T02:00:00Z"}]"#;
        let parsed: Vec<AgentMessage> = serde_json::from_str(json).unwrap();
        assert_eq!(parsed[0].title.as_deref(), Some("Hi"));
    }
}
