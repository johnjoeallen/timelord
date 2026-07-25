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

use std::path::{Path, PathBuf};
use std::sync::Arc;

use chrono::Utc;
use tokio::sync::Mutex as AsyncMutex;
use tracing::{error, info, warn};

use queue::EventQueue;
use store::{Store, StoreError};
use tracker::{EndReason, SessionEvent, Transition, UsageTracker};

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
        Ok(Self { tracker, store, open_session_id, event_queue: None })
    }

    /// Also report every tracker notification to the controller via `queue`
    /// (as the matching `USER_LOGON`/`USER_LOCK`/... event — see
    /// `events::from_session_event`).
    pub fn with_event_queue(mut self, queue: Arc<AsyncMutex<EventQueue>>) -> Self {
        self.event_queue = Some(queue);
        self
    }

    /// Feeds a platform event into the tracker and persists any resulting
    /// session start/end, and — if wired to one — mirrors the raw event
    /// into the reporting queue regardless of whether it changed usage
    /// session state.
    pub fn handle(&mut self, event: SessionEvent) -> Result<(), StoreError> {
        let now = Utc::now();
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

        if let Some(queue) = &self.event_queue {
            let queued = events::from_session_event(event);
            // This runs on the synchronous platform-pump thread, never on a
            // Tokio worker, so blocking on the async mutex here is safe and
            // avoids needing an async `handle`.
            if let Err(err) = queue.blocking_lock().enqueue(&queued, now) {
                error!(?err, "failed to persist reporting event");
            }
        }

        Ok(())
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
