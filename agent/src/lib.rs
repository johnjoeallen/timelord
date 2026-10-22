pub mod agent;
pub mod config;
pub mod controller_client;
pub mod discovery;
pub mod events;
pub mod platform;
pub mod protocol;
pub mod queue;
pub mod schedule;
#[cfg(windows)]
pub mod service_win;
pub mod store;
pub mod tracker;

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex as StdMutex};

use chrono::Utc;
use tokio::sync::Mutex as AsyncMutex;
use tracing::{error, info, warn};

use queue::EventQueue;
use store::{Store, StoreError};
use tracker::{EndReason, SessionEvent, SessionId, Transition, UsageTracker};

/// Live view of "who is logged on and how" that the async networking side
/// (heartbeats — see `agent::heartbeat_loop`) reads from, since it has no
/// other way to see what the synchronous platform-pump thread observes.
/// Defaults to "no one's logged on" until the first session notification
/// arrives (or `UsageRecorder::open` seeds it from any session that was
/// already active when the agent started — see `platform::windows::run`).
#[derive(Debug, Clone)]
pub struct SessionSnapshot {
    pub username: Option<String>,
    pub state: String,
}

impl Default for SessionSnapshot {
    fn default() -> Self {
        Self { username: None, state: "NO_SESSION".to_string() }
    }
}

/// Directory the agent persists its state under. Honours `TIMELORD_DATA_DIR`
/// (used for local development and tests) and otherwise defaults to
/// `C:\ProgramData\TimeLord` on Windows, matching the design brief's layout.
pub fn data_dir() -> anyhow::Result<PathBuf> {
    let path = match std::env::var_os("TIMELORD_DATA_DIR") {
        Some(dir) => PathBuf::from(dir),
        None if cfg!(windows) => PathBuf::from(r"C:\ProgramData\TimeLord"),
        None => PathBuf::from("./timelord-data"),
    };
    std::fs::create_dir_all(&path)?;
    Ok(path)
}

/// Ties the platform-independent [`UsageTracker`] to the local [`Store`],
/// so platform backends only have to feed in `SessionEvent`s. Optionally
/// also mirrors every event into the [`EventQueue`] for controller
/// reporting (design brief section 8) — kept optional so the existing
/// usage-only tests and dev flows don't need a queue at all.
pub struct UsageRecorder {
    tracker: UsageTracker,
    store: Store,
    open_session_id: Option<uuid::Uuid>,
    event_queue: Option<Arc<AsyncMutex<EventQueue>>>,
    /// Windows account per currently logged-on session, so a later event
    /// for the same session (lock/unlock/logoff/...) — which carries no
    /// username of its own — can still be reported and reflected in
    /// `snapshot` correctly.
    session_usernames: HashMap<SessionId, String>,
    snapshot: Arc<StdMutex<SessionSnapshot>>,
}

impl UsageRecorder {
    /// Opens the on-disk store at `db_path` and recovers any usage session
    /// left dangling by a previous run (e.g. the agent was killed rather
    /// than shut down cleanly).
    pub fn open(db_path: &Path) -> Result<Self, StoreError> {
        let store = Store::open(db_path)?;
        Self::from_store(store)
    }

    fn from_store(store: Store) -> Result<Self, StoreError> {
        let (tracker, open_session_id) = match store.find_dangling_session()? {
            Some((id, started_at)) => {
                warn!(session_id = %id, %started_at, "recovering usage session left open by a previous run");
                (UsageTracker::resume_open_session(started_at), Some(id))
            }
            None => (UsageTracker::new(), None),
        };
        Ok(Self {
            tracker,
            store,
            open_session_id,
            event_queue: None,
            session_usernames: HashMap::new(),
            snapshot: Arc::new(StdMutex::new(SessionSnapshot::default())),
        })
    }

    /// Also report every tracker notification to the controller via `queue`
    /// (as the matching `USER_LOGON`/`USER_LOCK`/... event — see
    /// `events::from_session_event`).
    pub fn with_event_queue(mut self, queue: Arc<AsyncMutex<EventQueue>>) -> Self {
        self.event_queue = Some(queue);
        self
    }

    /// A handle to the live "who's logged on" snapshot, shared with the
    /// async networking side (`agent::heartbeat_loop`) so heartbeats report
    /// the actual current user/session state instead of a hardcoded stub.
    pub fn snapshot_handle(&self) -> Arc<StdMutex<SessionSnapshot>> {
        self.snapshot.clone()
    }

    /// Feeds a platform event into the tracker and persists any resulting
    /// session start/end, and — if wired to one — mirrors the raw event
    /// into the reporting queue regardless of whether it changed usage
    /// session state. `username` is the Windows account for the event's
    /// session, if the caller was able to resolve one — cached whenever
    /// present, regardless of event type, so a session first observed via
    /// something other than `Logon` (e.g. the agent started while already
    /// logged on but disconnected, then the user reconnects) still ends up
    /// with a known username instead of silently staying blank.
    pub fn handle(&mut self, event: SessionEvent, username: Option<String>) -> Result<(), StoreError> {
        self.handle_at(event, username, Utc::now())
    }

    /// Handles a notification using the timestamp captured at the platform
    /// boundary. Keeping this separate makes it possible for platform tests
    /// (and future OS APIs that provide an event timestamp) to avoid replacing
    /// event time with a later polling/delivery time.
    pub fn handle_at(&mut self, event: SessionEvent, username: Option<String>, now: chrono::DateTime<Utc>) -> Result<(), StoreError> {

        // WTS notifications can be observed more than once by a service
        // restart/re-registration race. A repeated logon is not a second
        // session and must not create a second historical LOGON.
        if let SessionEvent::Logon(id) = event {
            if self.tracker.is_logged_on(id) {
                return Ok(());
            }
        }

        if let (Some(id), Some(name)) = (event.session_id(), &username) {
            self.session_usernames.insert(id, name.clone());
        }
        let event_username = event.session_id().and_then(|id| self.session_usernames.get(&id).cloned());

        let transition = self.tracker.apply(event, now);
        match &transition {
            Transition::Started { start } => {
                info!(%start, ?event, "usage session started");
                self.open_session_id = Some(self.store.start_session(*start)?);
            }
            Transition::Ended(record) => {
                info!(start = %record.start, end = %record.end, reason = ?record.end_reason, ?event, "usage session ended");
                if let Some(id) = self.open_session_id.take() {
                    self.store.end_session(id, record.end, record.end_reason)?;
                } else {
                    // Shouldn't happen: tracker says a session closed but we
                    // never recorded its ID. Persist it as a standalone
                    // record so the data isn't lost.
                    self.store.insert_closed_session(record)?;
                }
            }
            Transition::NoChange => {}
        }

        if let SessionEvent::Logoff(id) = event {
            self.session_usernames.remove(&id);
        }
        self.refresh_snapshot(event);
        if let Some(queue) = &self.event_queue {
            let queued = events::from_session_event(event, event_username.clone());
            let activity_transition = match (&transition, event) {
                (Transition::Started { .. }, _) => Some(true),
                // LOGOFF is a session boundary, not an activity transition;
                // preserve the distinct LOGOFF event without inventing an
                // extra INACTIVE at the same instant.
                (Transition::Ended(_), SessionEvent::Logoff(_)) => None,
                (Transition::Ended(_), _) => Some(false),
                (Transition::NoChange, _) => None,
            };
            // This runs on the synchronous platform-pump thread, never on a
            // Tokio worker, so blocking on the async mutex here is safe and
            // avoids needing an async `handle`.
            if let Err(err) = queue.blocking_lock().enqueue(&queued, now) {
                error!(?err, "failed to persist reporting event");
            }
            if let Some(active) = activity_transition {
                // Enqueueing the raw session event first preserves the
                // meaningful order LOGON -> ACTIVE (and LOCK -> INACTIVE).
                let activity = events::activity_event(active, now, event_username.clone());
                if let Err(err) = queue.blocking_lock().enqueue(&activity, now) {
                    error!(?err, "failed to persist activity transition");
                }
            }
        }

        Ok(())
    }

    /// Recomputes the shared snapshot from current tracker/session state.
    /// Called after every event so heartbeats always reflect reality rather
    /// than the value at agent startup.
    fn refresh_snapshot(&self, event: SessionEvent) {
        // Single-user-at-a-time is the common case this is designed for;
        // with multiple concurrent sessions (fast user switching, RDP) this
        // just reports one of them rather than modelling all of them.
        let username = self.session_usernames.values().next().cloned();
        let state = if self.tracker.is_active() && username.is_some() {
            "ACTIVE"
        } else if self.tracker.is_active() {
            // A usage session is open but no session-change event for it has
            // ever resolved a username (should be rare now that every event
            // type attempts resolution, not just Logon) — never claim ACTIVE
            // without knowing who; report the same conservative state used
            // when we merely find a pre-existing session at startup.
            "LOCKED"
        } else {
            match event {
                SessionEvent::Suspend => "SUSPENDED",
                SessionEvent::Logoff(_) => "NO_SESSION",
                SessionEvent::Lock(_) => "LOCKED",
                SessionEvent::ConsoleDisconnect(_) | SessionEvent::RemoteDisconnect(_) => "DISCONNECTED",
                _ => "NO_SESSION",
            }
        };
        let mut snapshot = self.snapshot.lock().unwrap();
        snapshot.state = state.to_string();
        snapshot.username = username;
    }

    /// Call on clean agent shutdown so an in-progress session isn't left
    /// dangling in the store (it would otherwise be recovered and closed
    /// with `AgentRestart` next launch, which is accurate but coarser).
    pub fn shutdown(&mut self) -> Result<(), StoreError> {
        if let (Some(record), Some(id)) = (self.tracker.close(Utc::now(), EndReason::AgentShutdown), self.open_session_id.take())
        {
            self.store.end_session(id, record.end, record.end_reason)?;
        }
        Ok(())
    }

    pub fn is_active(&self) -> bool {
        self.tracker.is_active()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use chrono::TimeZone;
    use tokio::sync::Mutex as AsyncMutex;

    fn recorder() -> UsageRecorder {
        UsageRecorder::from_store(store::Store::open_in_memory().unwrap()).unwrap()
    }

    #[test]
    fn username_is_resolved_from_a_non_logon_event_when_logon_was_never_observed() {
        // Simulates the agent starting after the user already logged on (so
        // no Logon notification ever arrived for this process), then
        // reconnecting via RDP/console — the first event able to resolve a
        // username.
        let mut recorder = recorder();
        recorder.handle(SessionEvent::ConsoleConnect(7), Some("alice".to_string())).unwrap();

        let snapshot = recorder.snapshot_handle();
        let snapshot = snapshot.lock().unwrap();
        assert_eq!(snapshot.state, "ACTIVE");
        assert_eq!(snapshot.username.as_deref(), Some("alice"));
    }

    #[test]
    fn active_session_without_a_known_username_reports_locked_not_active() {
        let mut recorder = recorder();
        recorder.handle(SessionEvent::ConsoleConnect(7), None).unwrap();

        let snapshot = recorder.snapshot_handle();
        let snapshot = snapshot.lock().unwrap();
        assert_eq!(snapshot.state, "LOCKED");
        assert_eq!(snapshot.username, None);
    }

    #[test]
    fn logoff_clears_username_and_reports_no_session() {
        let mut recorder = recorder();
        recorder.handle(SessionEvent::Logon(7), Some("alice".to_string())).unwrap();
        recorder.handle(SessionEvent::Logoff(7), None).unwrap();

        let snapshot = recorder.snapshot_handle();
        let snapshot = snapshot.lock().unwrap();
        assert_eq!(snapshot.state, "NO_SESSION");
        assert_eq!(snapshot.username, None);
    }

    fn recorder_with_queue() -> (UsageRecorder, Arc<AsyncMutex<EventQueue>>) {
        let queue = Arc::new(AsyncMutex::new(EventQueue::open_in_memory().unwrap()));
        let recorder = recorder().with_event_queue(queue.clone());
        (recorder, queue)
    }

    #[test]
    fn logon_persists_logon_then_active_and_updates_dashboard() {
        let (mut recorder, queue) = recorder_with_queue();
        let at = Utc.timestamp_opt(1_700_000_000, 0).unwrap();
        recorder.handle_at(SessionEvent::Logon(7), Some("alice".into()), at).unwrap();

        assert_eq!(queue.blocking_lock().event_types().unwrap(),
                   vec![protocol::EventType::UserLogon, protocol::EventType::Active]);
        assert_eq!(recorder.snapshot_handle().lock().unwrap().state, "ACTIVE");
    }

    #[test]
    fn activity_transitions_are_persisted_and_logoff_is_not_a_second_logon() {
        let (mut recorder, queue) = recorder_with_queue();
        let at = Utc.timestamp_opt(1_700_000_000, 0).unwrap();
        recorder.handle_at(SessionEvent::Logon(7), Some("alice".into()), at).unwrap();
        recorder.handle_at(SessionEvent::Lock(7), None, at + chrono::Duration::seconds(10)).unwrap();
        recorder.handle_at(SessionEvent::Unlock(7), None, at + chrono::Duration::seconds(20)).unwrap();
        recorder.handle_at(SessionEvent::Logon(7), Some("alice".into()), at + chrono::Duration::seconds(21)).unwrap();
        recorder.handle_at(SessionEvent::Logoff(7), None, at + chrono::Duration::seconds(30)).unwrap();

        assert_eq!(queue.blocking_lock().event_types().unwrap(), vec![
            protocol::EventType::UserLogon, protocol::EventType::Active,
            protocol::EventType::UserLock, protocol::EventType::Inactive,
            protocol::EventType::UserUnlock, protocol::EventType::Active,
            protocol::EventType::UserLogoff,
        ]);
    }

    #[test]
    fn startup_reconciliation_records_login_for_active_session() {
        let (mut recorder, queue) = recorder_with_queue();
        recorder.handle_at(SessionEvent::Logon(7), Some("alice".into()), Utc::now()).unwrap();
        assert_eq!(queue.blocking_lock().event_types().unwrap(), vec![protocol::EventType::UserLogon, protocol::EventType::Active]);
        assert_eq!(recorder.snapshot_handle().lock().unwrap().state, "ACTIVE");
    }
}
