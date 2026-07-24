pub mod platform;
#[cfg(windows)]
pub mod service_win;
pub mod store;
pub mod tracker;

use std::path::{Path, PathBuf};

use chrono::Utc;
use tracing::{info, warn};

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
/// so platform backends only have to feed in `SessionEvent`s.
pub struct UsageRecorder {
    tracker: UsageTracker,
    store: Store,
    open_session_id: Option<uuid::Uuid>,
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
        Ok(Self { tracker, store, open_session_id })
    }

    /// Feeds a platform event into the tracker and persists any resulting
    /// session start/end.
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
