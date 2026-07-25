//! Persistent local event queue (design brief section 9). Events are
//! written to SQLite *before* any delivery attempt, so a crash or forced
//! power-off between "event happened" and "controller acknowledged it"
//! never loses the event — the next start just finds it still pending.

use std::collections::HashMap;
use std::path::Path;
use std::time::Duration;

use chrono::{DateTime, Utc};
use rusqlite::{params, Connection};
use uuid::Uuid;

use crate::protocol::{EventItem, EventSeverity, EventSource, EventType};

/// `10s, 30s, 1m, 5m, 15m, 30m` (design brief section 9), holding at the
/// last value for any further attempt.
const BACKOFF_SCHEDULE_SECONDS: [u64; 6] = [10, 30, 60, 300, 900, 1800];

/// After this many failed attempts an event is treated as permanently
/// undeliverable and dropped rather than retried forever — roughly a day of
/// retrying at the 30-minute ceiling.
const MAX_ATTEMPTS: u32 = 48;

/// Hard cap on queued rows; enqueueing past this discards the oldest
/// undelivered events rather than growing without bound.
const MAX_QUEUE_SIZE: i64 = 5000;

#[derive(Debug, thiserror::Error)]
pub enum QueueError {
    #[error("sqlite error: {0}")]
    Sqlite(#[from] rusqlite::Error),
    #[error("serialization error: {0}")]
    Serde(#[from] serde_json::Error),
}

#[derive(Debug, Clone, PartialEq)]
pub struct QueuedEvent {
    pub event_id: Uuid,
    pub event_type: EventType,
    pub occurred_at: DateTime<Utc>,
    pub severity: EventSeverity,
    pub source: EventSource,
    pub session_id: Option<Uuid>,
    pub username: Option<String>,
    pub data: HashMap<String, serde_json::Value>,
}

impl QueuedEvent {
    pub fn to_event_item(&self) -> EventItem {
        EventItem {
            event_id: self.event_id,
            event_type: self.event_type,
            occurred_at: self.occurred_at,
            severity: self.severity,
            source: self.source,
            session_id: self.session_id,
            username: self.username.clone(),
            data: self.data.clone(),
        }
    }
}

/// A row pulled off the queue for delivery, along with the DB row id needed
/// to later mark it delivered/failed and its current attempt count.
#[derive(Debug, Clone)]
pub struct PendingDelivery {
    pub row_id: i64,
    pub event: QueuedEvent,
    pub attempt_count: u32,
}

pub struct EventQueue {
    conn: Connection,
}

impl EventQueue {
    pub fn open(path: &Path) -> Result<Self, QueueError> {
        let conn = Connection::open(path)?;
        Self::init(conn)
    }

    #[cfg(test)]
    pub fn open_in_memory() -> Result<Self, QueueError> {
        let conn = Connection::open_in_memory()?;
        Self::init(conn)
    }

    fn init(conn: Connection) -> Result<Self, QueueError> {
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS event_queue (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                event_id        TEXT NOT NULL UNIQUE,
                event_type      TEXT NOT NULL,
                occurred_at     TEXT NOT NULL,
                severity        TEXT NOT NULL,
                source          TEXT NOT NULL,
                session_id      TEXT,
                username        TEXT,
                payload_json    TEXT NOT NULL,
                attempt_count   INTEGER NOT NULL DEFAULT 0,
                next_attempt_at TEXT NOT NULL,
                created_at      TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_event_queue_next_attempt ON event_queue(next_attempt_at);",
        )?;
        Ok(Self { conn })
    }

    /// Persists `event` immediately, before any delivery attempt is made.
    /// A duplicate `event_id` (the same event enqueued twice locally, e.g.
    /// after an agent restart replays an in-memory step) is a silent no-op.
    pub fn enqueue(&self, event: &QueuedEvent, now: DateTime<Utc>) -> Result<(), QueueError> {
        let payload_json = serde_json::to_string(&event.data)?;
        self.conn.execute(
            "INSERT INTO event_queue
                (event_id, event_type, occurred_at, severity, source, session_id, username, payload_json, attempt_count, next_attempt_at, created_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, 0, ?9, ?9)
             ON CONFLICT(event_id) DO NOTHING",
            params![
                event.event_id.to_string(),
                serde_json::to_string(&event.event_type)?,
                event.occurred_at.to_rfc3339(),
                serde_json::to_string(&event.severity)?,
                serde_json::to_string(&event.source)?,
                event.session_id.map(|id| id.to_string()),
                event.username,
                payload_json,
                now.to_rfc3339(),
            ],
        )?;
        self.enforce_max_size()?;
        Ok(())
    }

    fn enforce_max_size(&self) -> Result<(), QueueError> {
        self.enforce_max_size_with_cap(MAX_QUEUE_SIZE)
    }

    fn enforce_max_size_with_cap(&self, cap: i64) -> Result<(), QueueError> {
        let count: i64 = self.conn.query_row("SELECT COUNT(*) FROM event_queue", [], |row| row.get(0))?;
        let overflow = count - cap;
        if overflow > 0 {
            let discarded = self.conn.execute(
                "DELETE FROM event_queue WHERE id IN (
                    SELECT id FROM event_queue ORDER BY created_at ASC LIMIT ?1
                )",
                params![overflow],
            )?;
            tracing::warn!(discarded, cap, "event queue over capacity; discarded oldest events");
        }
        Ok(())
    }

    /// Rows whose `next_attempt_at` has passed, oldest first, up to `limit`.
    pub fn due_batch(&self, now: DateTime<Utc>, limit: usize) -> Result<Vec<PendingDelivery>, QueueError> {
        let mut stmt = self.conn.prepare(
            "SELECT id, event_id, event_type, occurred_at, severity, source, session_id, username, payload_json, attempt_count
             FROM event_queue
             WHERE next_attempt_at <= ?1
             ORDER BY created_at ASC
             LIMIT ?2",
        )?;
        let rows = stmt
            .query_map(params![now.to_rfc3339(), limit as i64], |row| {
                let event_id: String = row.get(1)?;
                let event_type: String = row.get(2)?;
                let occurred_at: String = row.get(3)?;
                let severity: String = row.get(4)?;
                let source: String = row.get(5)?;
                let session_id: Option<String> = row.get(6)?;
                let username: Option<String> = row.get(7)?;
                let payload_json: String = row.get(8)?;
                let attempt_count: i64 = row.get(9)?;
                Ok((
                    row.get::<_, i64>(0)?,
                    event_id,
                    event_type,
                    occurred_at,
                    severity,
                    source,
                    session_id,
                    username,
                    payload_json,
                    attempt_count,
                ))
            })?
            .collect::<Result<Vec<_>, _>>()?;

        rows.into_iter()
            .map(|(row_id, event_id, event_type, occurred_at, severity, source, session_id, username, payload_json, attempt_count)| {
                Ok(PendingDelivery {
                    row_id,
                    attempt_count: attempt_count as u32,
                    event: QueuedEvent {
                        event_id: Uuid::parse_str(&event_id).expect("stored event_id is a valid UUID"),
                        event_type: serde_json::from_str(&event_type)?,
                        occurred_at: DateTime::parse_from_rfc3339(&occurred_at)
                            .expect("stored timestamp is valid RFC3339")
                            .with_timezone(&Utc),
                        severity: serde_json::from_str(&severity)?,
                        source: serde_json::from_str(&source)?,
                        session_id: session_id.map(|s| Uuid::parse_str(&s).expect("stored session_id is a valid UUID")),
                        username,
                        data: serde_json::from_str(&payload_json)?,
                    },
                })
            })
            .collect()
    }

    /// Removes a successfully (or duplicate-acknowledged — see design brief
    /// section 9, "treat duplicate acknowledgement as successful delivery")
    /// delivered event.
    pub fn mark_delivered(&self, row_id: i64) -> Result<(), QueueError> {
        self.conn.execute("DELETE FROM event_queue WHERE id = ?1", params![row_id])?;
        Ok(())
    }

    /// Convenience for callers (e.g. `timelord-agent test-event`) that only
    /// have the event's own ID, not its row ID. A no-op if the event isn't
    /// queued (e.g. it was already delivered).
    pub fn mark_delivered_by_event_id(&self, event_id: Uuid) -> Result<(), QueueError> {
        self.conn.execute("DELETE FROM event_queue WHERE event_id = ?1", params![event_id.to_string()])?;
        Ok(())
    }

    /// Records a failed delivery attempt, scheduling the next retry via the
    /// backoff schedule, or discards the event outright once it has been
    /// retried [`MAX_ATTEMPTS`] times.
    pub fn mark_failed(&self, row_id: i64, attempt_count: u32, now: DateTime<Utc>) -> Result<(), QueueError> {
        let next_attempt_count = attempt_count + 1;
        if next_attempt_count >= MAX_ATTEMPTS {
            tracing::warn!(row_id, attempt_count = next_attempt_count, "discarding permanently undeliverable event");
            self.conn.execute("DELETE FROM event_queue WHERE id = ?1", params![row_id])?;
            return Ok(());
        }
        let delay = backoff_delay(next_attempt_count);
        let next_attempt_at = now + chrono::Duration::from_std(delay).unwrap();
        self.conn.execute(
            "UPDATE event_queue SET attempt_count = ?2, next_attempt_at = ?3 WHERE id = ?1",
            params![row_id, next_attempt_count, next_attempt_at.to_rfc3339()],
        )?;
        Ok(())
    }

    pub fn len(&self) -> Result<usize, QueueError> {
        let count: i64 = self.conn.query_row("SELECT COUNT(*) FROM event_queue", [], |row| row.get(0))?;
        Ok(count as usize)
    }

    pub fn is_empty(&self) -> Result<bool, QueueError> {
        Ok(self.len()? == 0)
    }

    #[cfg(test)]
    fn find_by_event_id(&self, event_id: Uuid) -> Result<Option<i64>, QueueError> {
        use rusqlite::OptionalExtension;
        self.conn
            .query_row("SELECT id FROM event_queue WHERE event_id = ?1", params![event_id.to_string()], |row| row.get(0))
            .optional()
            .map_err(Into::into)
    }
}

/// `attempt` is 1-indexed (the attempt count *after* the failure just
/// recorded). Holds at the last schedule entry for any attempt beyond it.
fn backoff_delay(attempt: u32) -> Duration {
    let index = (attempt as usize).saturating_sub(1).min(BACKOFF_SCHEDULE_SECONDS.len() - 1);
    Duration::from_secs(BACKOFF_SCHEDULE_SECONDS[index])
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeZone;

    fn t(seconds: i64) -> DateTime<Utc> {
        Utc.timestamp_opt(1_700_000_000 + seconds, 0).unwrap()
    }

    fn sample_event(event_id: Uuid) -> QueuedEvent {
        QueuedEvent {
            event_id,
            event_type: EventType::AgentStarted,
            occurred_at: t(0),
            severity: EventSeverity::Info,
            source: EventSource::Agent,
            session_id: None,
            username: None,
            data: HashMap::from([("agentVersion".to_string(), serde_json::json!("0.1.0"))]),
        }
    }

    #[test]
    fn enqueued_event_is_immediately_due() {
        let queue = EventQueue::open_in_memory().unwrap();
        let id = Uuid::new_v4();
        queue.enqueue(&sample_event(id), t(0)).unwrap();

        let due = queue.due_batch(t(0), 10).unwrap();
        assert_eq!(due.len(), 1);
        assert_eq!(due[0].event.event_id, id);
        assert_eq!(due[0].attempt_count, 0);
    }

    #[test]
    fn round_trips_event_fields_including_data_payload() {
        let queue = EventQueue::open_in_memory().unwrap();
        let id = Uuid::new_v4();
        let mut event = sample_event(id);
        event.username = Some("jallen".into());
        event.session_id = Some(Uuid::new_v4());
        queue.enqueue(&event, t(0)).unwrap();

        let due = queue.due_batch(t(0), 10).unwrap();
        assert_eq!(due[0].event, event);
    }

    #[test]
    fn mark_delivered_removes_the_row() {
        let queue = EventQueue::open_in_memory().unwrap();
        let id = Uuid::new_v4();
        queue.enqueue(&sample_event(id), t(0)).unwrap();
        let due = queue.due_batch(t(0), 10).unwrap();

        queue.mark_delivered(due[0].row_id).unwrap();
        assert!(queue.is_empty().unwrap());
    }

    #[test]
    fn mark_failed_schedules_next_attempt_using_backoff_schedule() {
        let queue = EventQueue::open_in_memory().unwrap();
        let id = Uuid::new_v4();
        queue.enqueue(&sample_event(id), t(0)).unwrap();
        let due = queue.due_batch(t(0), 10).unwrap();

        queue.mark_failed(due[0].row_id, 0, t(0)).unwrap();

        // Not due again immediately...
        assert!(queue.due_batch(t(5), 10).unwrap().is_empty());
        // ...but is due after the first backoff step (10s).
        let due_again = queue.due_batch(t(10), 10).unwrap();
        assert_eq!(due_again.len(), 1);
        assert_eq!(due_again[0].attempt_count, 1);
    }

    #[test]
    fn backoff_follows_documented_schedule() {
        assert_eq!(backoff_delay(1), Duration::from_secs(10));
        assert_eq!(backoff_delay(2), Duration::from_secs(30));
        assert_eq!(backoff_delay(3), Duration::from_secs(60));
        assert_eq!(backoff_delay(4), Duration::from_secs(300));
        assert_eq!(backoff_delay(5), Duration::from_secs(900));
        assert_eq!(backoff_delay(6), Duration::from_secs(1800));
        // Holds at the ceiling for further attempts.
        assert_eq!(backoff_delay(20), Duration::from_secs(1800));
    }

    #[test]
    fn event_is_discarded_after_max_attempts() {
        let queue = EventQueue::open_in_memory().unwrap();
        let id = Uuid::new_v4();
        queue.enqueue(&sample_event(id), t(0)).unwrap();
        let row_id = queue.due_batch(t(0), 10).unwrap()[0].row_id;

        queue.mark_failed(row_id, MAX_ATTEMPTS - 1, t(0)).unwrap();

        assert!(queue.find_by_event_id(id).unwrap().is_none());
    }

    #[test]
    fn duplicate_local_enqueue_is_a_no_op() {
        let queue = EventQueue::open_in_memory().unwrap();
        let id = Uuid::new_v4();
        queue.enqueue(&sample_event(id), t(0)).unwrap();
        queue.enqueue(&sample_event(id), t(1)).unwrap();

        assert_eq!(queue.len().unwrap(), 1);
    }

    #[test]
    fn queue_over_capacity_discards_oldest_events() {
        let queue = EventQueue::open_in_memory().unwrap();
        let ids: Vec<Uuid> = (0..5).map(|_| Uuid::new_v4()).collect();
        for (i, id) in ids.iter().enumerate() {
            let event = sample_event(*id);
            // Bypass the real (5000) cap so this test stays fast; exercise
            // the same trimming logic with a small cap instead.
            let payload_json = serde_json::to_string(&event.data).unwrap();
            queue
                .conn
                .execute(
                    "INSERT INTO event_queue
                        (event_id, event_type, occurred_at, severity, source, session_id, username, payload_json, attempt_count, next_attempt_at, created_at)
                     VALUES (?1, ?2, ?3, ?4, ?5, NULL, NULL, ?6, 0, ?7, ?7)",
                    params![
                        event.event_id.to_string(),
                        serde_json::to_string(&event.event_type).unwrap(),
                        event.occurred_at.to_rfc3339(),
                        serde_json::to_string(&event.severity).unwrap(),
                        serde_json::to_string(&event.source).unwrap(),
                        payload_json,
                        t(i as i64).to_rfc3339(),
                    ],
                )
                .unwrap();
        }
        queue.enforce_max_size_with_cap(2).unwrap();

        assert_eq!(queue.len().unwrap(), 2);
        // The three oldest (by created_at) were discarded; the two newest remain.
        assert!(queue.find_by_event_id(ids[3]).unwrap().is_some());
        assert!(queue.find_by_event_id(ids[4]).unwrap().is_some());
        assert!(queue.find_by_event_id(ids[0]).unwrap().is_none());
    }

    #[test]
    fn due_batch_respects_limit_and_fifo_order() {
        let queue = EventQueue::open_in_memory().unwrap();
        let ids: Vec<Uuid> = (0..5).map(|_| Uuid::new_v4()).collect();
        for (i, id) in ids.iter().enumerate() {
            queue.enqueue(&sample_event(*id), t(i as i64)).unwrap();
        }

        let batch = queue.due_batch(t(10), 2).unwrap();
        assert_eq!(batch.len(), 2);
        assert_eq!(batch[0].event.event_id, ids[0]);
        assert_eq!(batch[1].event.event_id, ids[1]);
    }
}
