pub mod agent;
pub mod config;
pub mod controller_client;
pub mod discovery;
pub mod events;
pub mod platform;
pub mod protocol;
pub mod queue;
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

    /// Records who's logged into `session_id` when the agent discovers a
    /// session that was already active *before* it started observing
    /// (service restart, or a reboot with an existing interactive logon —
    /// see `platform::windows::seed_active_sessions`). Deliberately does
    /// *not* go through [`Self::handle`]: we have no way to tell "this is a
    /// brand-new login" apart from "the agent process simply restarted
    /// while the same login continued", and treating it as a fresh `Logon`
    /// would open a new usage session (and emit a `UserLogon` event) every
    /// time the service bounces, fragmenting one continuous stretch of
    /// usage into spurious back-to-back session rows. The next real
    /// lock/unlock/logoff still opens/closes a usage session normally;
    /// this only fixes up `current_user`/`session_state` for heartbeats in
    /// the meantime.
    ///
    /// Reports `LOCKED`, not `ACTIVE`: the plain `WTSActive`/`WTSConnected`
    /// connect-state this was seeded from doesn't distinguish a locked
    /// console session from an unlocked one. Callers that can positively
    /// confirm the session is actually unlocked (see
    /// `platform::windows::session_locked`, which queries `WTSSessionInfoEx`)
    /// should dispatch a real `Unlock` through [`Self::handle`] instead of
    /// calling this — this method is the fallback for when Windows itself
    /// doesn't know. `LOCKED` is the state that's true either way (logged
    /// in, presence unconfirmed) instead of overclaiming active use; a
    /// subsequent real `Unlock` corrects it to `ACTIVE`.
    pub fn seed_current_user(&mut self, session_id: SessionId, username: String) {
        self.session_usernames.insert(session_id, username);
        let mut snapshot = self.snapshot.lock().unwrap();
        snapshot.state = "LOCKED".to_string();
        snapshot.username = self.session_usernames.values().next().cloned();
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
        let now = Utc::now();

        if let (Some(id), Some(name)) = (event.session_id(), &username) {
            self.session_usernames.insert(id, name.clone());
        }
        let event_username = event.session_id().and_then(|id| self.session_usernames.get(&id).cloned());

        match self.tracker.apply(event, now) {
            Transition::Started { start } => {
                info!(%start, ?event, "usage session started");
                self.open_session_id = Some(self.store.start_session(start)?);
            }
            Transition::Ended(record) => {
                info!(start = %record.start, end = %record.end, reason = ?record.end_reason, ?event, "usage session ended");
                if let Some(id) = self.open_session_id.take() {
                    self.store.end_session(id, record.end, record.end_reason)?;
                } else {
                    // Shouldn't happen: tracker says a session closed but we
                    // never recorded its ID. Persist it as a standalone
                    // record so the data isn't lost.
                    self.store.insert_closed_session(&record)?;
                }
            }
            Transition::NoChange => {}
        }

        if let SessionEvent::Logoff(id) = event {
            self.session_usernames.remove(&id);
        }
        self.refresh_snapshot(event);

        if let Some(queue) = &self.event_queue {
            let queued = events::from_session_event(event, event_username);
            // This runs on the synchronous platform-pump thread, never on a
            // Tokio worker, so blocking on the async mutex here is safe and
            // avoids needing an async `handle`.
            if let Err(err) = queue.blocking_lock().enqueue(&queued, now) {
                error!(?err, "failed to persist reporting event");
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
}
