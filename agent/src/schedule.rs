//! The controller-resolved on/off schedule the agent enforces.
//!
//! The controller turns calendars, overrides and extensions into concrete
//! off windows; the agent only has to answer "is `now` inside one?". The
//! last schedule received is cached on disk so enforcement keeps working
//! while the controller is unreachable.

use std::path::Path;

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum PowerAction {
    Sleep,
    Shutdown,
}

impl PowerAction {
    /// Name used in power-action events.
    pub fn event_name(self) -> &'static str {
        match self {
            PowerAction::Sleep => "SUSPEND",
            PowerAction::Shutdown => "SHUTDOWN",
        }
    }
}

/// A span, `[off_at, on_at)`, during which the device must not be on.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OffWindow {
    pub off_at: DateTime<Utc>,
    pub on_at: DateTime<Utc>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Schedule {
    pub revision: String,
    pub action: PowerAction,
    pub timezone: String,
    pub windows: Vec<OffWindow>,
}

impl Schedule {
    /// The window containing `now`, if the device should be off right now.
    pub fn active_window(&self, now: DateTime<Utc>) -> Option<&OffWindow> {
        self.windows.iter().find(|w| w.off_at <= now && now < w.on_at)
    }

    /// Start of the next window after `now`, used to sleep until it begins.
    pub fn next_off_at(&self, now: DateTime<Utc>) -> Option<DateTime<Utc>> {
        self.windows.iter().map(|w| w.off_at).filter(|t| *t > now).min()
    }
}

/// Whether the cached schedule may be enforced yet.
///
/// The cache can be stale: a calendar edit or an extension may have happened while the device was
/// off or asleep. So enforcement waits until a controller response has confirmed the schedule
/// since startup (or since resuming from sleep). If the controller stays unreachable for
/// `grace`, the cache is enforced anyway, so an offline device is still governed.
pub fn enforcement_ready(synced: bool, unsynced_for: std::time::Duration, grace: std::time::Duration) -> bool {
    synced || unsynced_for >= grace
}

pub fn load(path: &Path) -> Option<Schedule> {
    let text = std::fs::read_to_string(path).ok()?;
    match serde_json::from_str(&text) {
        Ok(schedule) => Some(schedule),
        Err(err) => {
            tracing::warn!(?err, "ignoring unreadable cached schedule");
            None
        }
    }
}

/// Persists (or, for `None`, removes) the cached schedule. Written via a
/// temp file + rename so a crash mid-write can't leave a truncated cache.
pub fn save(path: &Path, schedule: Option<&Schedule>) -> std::io::Result<()> {
    let Some(schedule) = schedule else {
        return match std::fs::remove_file(path) {
            Err(err) if err.kind() != std::io::ErrorKind::NotFound => Err(err),
            _ => Ok(()),
        };
    };
    let tmp = path.with_extension("json.tmp");
    std::fs::write(&tmp, serde_json::to_vec_pretty(schedule)?)?;
    std::fs::rename(tmp, path)
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeZone;

    fn at(h: u32, m: u32) -> DateTime<Utc> {
        Utc.with_ymd_and_hms(2026, 10, 26, h, m, 0).unwrap()
    }

    fn schedule() -> Schedule {
        Schedule {
            revision: "r1".into(),
            action: PowerAction::Sleep,
            timezone: "Europe/Dublin".into(),
            windows: vec![OffWindow { off_at: at(22, 30), on_at: at(23, 59) }],
        }
    }

    #[test]
    fn window_is_half_open() {
        let s = schedule();
        assert!(s.active_window(at(22, 29)).is_none());
        assert!(s.active_window(at(22, 30)).is_some());
        assert!(s.active_window(at(23, 59)).is_none());
    }

    #[test]
    fn next_off_at_ignores_past_and_current() {
        let s = schedule();
        assert_eq!(s.next_off_at(at(12, 0)), Some(at(22, 30)));
        assert_eq!(s.next_off_at(at(22, 30)), None);
    }

    #[test]
    fn enforcement_waits_for_a_sync_then_falls_back_to_the_cache() {
        use std::time::Duration;
        let grace = Duration::from_secs(90);
        assert!(!enforcement_ready(false, Duration::from_secs(5), grace), "just started: wait for the controller");
        assert!(!enforcement_ready(false, Duration::from_secs(89), grace));
        assert!(enforcement_ready(false, Duration::from_secs(90), grace), "controller unreachable: enforce the cache");
        assert!(enforcement_ready(true, Duration::ZERO, grace), "confirmed by the controller");
    }

    #[test]
    fn deserializes_controller_json() {
        let json = r#"{"revision":"abc","action":"SHUTDOWN","timezone":"Europe/Dublin",
            "windows":[{"offAt":"2026-10-26T22:30:00Z","onAt":"2026-10-27T06:30:00Z"}]}"#;
        let s: Schedule = serde_json::from_str(json).unwrap();
        assert_eq!(s.action, PowerAction::Shutdown);
        assert_eq!(s.windows.len(), 1);
    }

    #[test]
    fn save_and_load_round_trip_and_clear() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("schedule.json");
        save(&path, Some(&schedule())).unwrap();
        assert_eq!(load(&path), Some(schedule()));
        save(&path, None).unwrap();
        assert_eq!(load(&path), None);
        save(&path, None).unwrap(); // clearing twice is fine
    }
}
