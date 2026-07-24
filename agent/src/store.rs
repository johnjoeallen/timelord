//! Local persistence for usage-session records.
//!
//! Sessions are written as soon as they start (with `end` left `NULL`) and
//! updated when they end, so a crash or forced power-off doesn't silently
//! lose the fact that a session was open. On the next startup the agent can
//! find any dangling open row and close it defensively — see
//! [`Store::recover_dangling_session`].

use chrono::{DateTime, Utc};
use rusqlite::{params, Connection, OptionalExtension};
use uuid::Uuid;

use crate::tracker::{EndReason, UsageSessionRecord};

#[derive(Debug, thiserror::Error)]
pub enum StoreError {
    #[error("sqlite error: {0}")]
    Sqlite(#[from] rusqlite::Error),
}

pub struct Store {
    conn: Connection,
}

#[cfg(test)]
type StoredSession = (Uuid, DateTime<Utc>, Option<DateTime<Utc>>);

impl Store {
    /// Opens (creating if necessary) the SQLite database at `path` and
    /// applies schema migrations.
    pub fn open(path: &std::path::Path) -> Result<Self, StoreError> {
        let conn = Connection::open(path)?;
        Self::init(conn)
    }

    /// In-memory store, used by tests.
    #[cfg(test)]
    pub fn open_in_memory() -> Result<Self, StoreError> {
        let conn = Connection::open_in_memory()?;
        Self::init(conn)
    }

    fn init(conn: Connection) -> Result<Self, StoreError> {
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.pragma_update(None, "synchronous", "FULL")?;
        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS usage_session (
                id          TEXT PRIMARY KEY,
                start_utc   TEXT NOT NULL,
                end_utc     TEXT,
                end_reason  TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_usage_session_start ON usage_session(start_utc);",
        )?;
        Ok(Self { conn })
    }

    /// Records the start of a new usage session and returns its ID so the
    /// caller can later close it out with [`Store::end_session`].
    pub fn start_session(&self, start: DateTime<Utc>) -> Result<Uuid, StoreError> {
        let id = Uuid::new_v4();
        self.conn.execute(
            "INSERT INTO usage_session (id, start_utc, end_utc, end_reason) VALUES (?1, ?2, NULL, NULL)",
            params![id.to_string(), start.to_rfc3339()],
        )?;
        Ok(id)
    }

    pub fn end_session(&self, id: Uuid, end: DateTime<Utc>, reason: EndReason) -> Result<(), StoreError> {
        self.conn.execute(
            "UPDATE usage_session SET end_utc = ?2, end_reason = ?3 WHERE id = ?1",
            params![id.to_string(), end.to_rfc3339(), reason_to_str(reason)],
        )?;
        Ok(())
    }

    /// Finds the most recent session with no `end_utc`, if any. Returns
    /// `(id, start)` so the caller can feed it into
    /// `UsageTracker::resume_open_session` and then close it once the
    /// current time is known.
    pub fn find_dangling_session(&self) -> Result<Option<(Uuid, DateTime<Utc>)>, StoreError> {
        let row: Option<(String, String)> = self
            .conn
            .query_row(
                "SELECT id, start_utc FROM usage_session WHERE end_utc IS NULL ORDER BY start_utc DESC LIMIT 1",
                [],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .optional()?;
        Ok(row.map(|(id, start)| {
            (
                Uuid::parse_str(&id).expect("stored session id is a valid UUID"),
                DateTime::parse_from_rfc3339(&start)
                    .expect("stored timestamp is valid RFC3339")
                    .with_timezone(&Utc),
            )
        }))
    }

    /// Convenience used when the tracker hands back a fully-closed record in
    /// one step (e.g. `NoChange` was skipped and we already know start+end).
    pub fn insert_closed_session(&self, record: &UsageSessionRecord) -> Result<Uuid, StoreError> {
        let id = Uuid::new_v4();
        self.conn.execute(
            "INSERT INTO usage_session (id, start_utc, end_utc, end_reason) VALUES (?1, ?2, ?3, ?4)",
            params![
                id.to_string(),
                record.start.to_rfc3339(),
                record.end.to_rfc3339(),
                reason_to_str(record.end_reason)
            ],
        )?;
        Ok(id)
    }

    #[cfg(test)]
    pub fn all_sessions(&self) -> Result<Vec<StoredSession>, StoreError> {
        let mut stmt = self
            .conn
            .prepare("SELECT id, start_utc, end_utc FROM usage_session ORDER BY start_utc ASC")?;
        let rows = stmt
            .query_map([], |row| {
                let id: String = row.get(0)?;
                let start: String = row.get(1)?;
                let end: Option<String> = row.get(2)?;
                Ok((id, start, end))
            })?
            .collect::<Result<Vec<_>, _>>()?;
        Ok(rows
            .into_iter()
            .map(|(id, start, end)| {
                (
                    Uuid::parse_str(&id).unwrap(),
                    DateTime::parse_from_rfc3339(&start).unwrap().with_timezone(&Utc),
                    end.map(|e| DateTime::parse_from_rfc3339(&e).unwrap().with_timezone(&Utc)),
                )
            })
            .collect())
    }
}

fn reason_to_str(reason: EndReason) -> &'static str {
    match reason {
        EndReason::Lock => "LOCK",
        EndReason::Logoff => "LOGOFF",
        EndReason::Suspend => "SUSPEND",
        EndReason::ConsoleDisconnect => "CONSOLE_DISCONNECT",
        EndReason::RemoteDisconnect => "REMOTE_DISCONNECT",
        EndReason::AgentRestart => "AGENT_RESTART",
        EndReason::AgentShutdown => "AGENT_SHUTDOWN",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeZone;

    fn t(seconds: i64) -> DateTime<Utc> {
        Utc.timestamp_opt(1_700_000_000 + seconds, 0).unwrap()
    }

    #[test]
    fn start_then_end_round_trips() {
        let store = Store::open_in_memory().unwrap();
        let id = store.start_session(t(0)).unwrap();
        store.end_session(id, t(100), EndReason::Lock).unwrap();

        let sessions = store.all_sessions().unwrap();
        assert_eq!(sessions.len(), 1);
        assert_eq!(sessions[0].0, id);
        assert_eq!(sessions[0].1, t(0));
        assert_eq!(sessions[0].2, Some(t(100)));
    }

    #[test]
    fn dangling_session_is_found_until_closed() {
        let store = Store::open_in_memory().unwrap();
        let id = store.start_session(t(0)).unwrap();

        let dangling = store.find_dangling_session().unwrap();
        assert_eq!(dangling, Some((id, t(0))));

        store.end_session(id, t(10), EndReason::AgentRestart).unwrap();
        assert_eq!(store.find_dangling_session().unwrap(), None);
    }

    #[test]
    fn insert_closed_session_writes_both_ends_at_once() {
        let store = Store::open_in_memory().unwrap();
        let record = UsageSessionRecord { start: t(0), end: t(5), end_reason: EndReason::Suspend };
        store.insert_closed_session(&record).unwrap();

        let sessions = store.all_sessions().unwrap();
        assert_eq!(sessions.len(), 1);
        assert_eq!(sessions[0].1, t(0));
        assert_eq!(sessions[0].2, Some(t(5)));
    }
}
