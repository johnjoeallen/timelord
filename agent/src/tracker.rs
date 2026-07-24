//! Platform-independent usage-session state machine.
//!
//! The tracker turns low-level Windows session/power notifications into
//! "usage sessions": contiguous spans of time during which the device was
//! actually being used by someone at the console (or over RDP), as opposed
//! to merely being powered on. It has no knowledge of Win32 APIs so it can
//! be exercised by ordinary unit tests on any platform.

use std::collections::HashMap;

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

/// A Windows terminal-services session identifier (as returned by
/// `WTSRegisterSessionNotification` callbacks).
pub type SessionId = u32;

/// Events the platform layer feeds into the tracker. These map fairly
/// directly onto `WM_WTSSESSION_CHANGE` reasons and power-broadcast
/// notifications, but are decoupled from the Win32 constants so the state
/// machine stays testable without the `windows` crate.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SessionEvent {
    /// A user has logged on to a session. The session starts unlocked.
    Logon(SessionId),
    /// A user has logged off; the session no longer exists.
    Logoff(SessionId),
    /// The session's workstation was locked (still logged on, not visible).
    Lock(SessionId),
    /// The session's workstation was unlocked.
    Unlock(SessionId),
    /// The session became the visible console session (e.g. fast user
    /// switching back to this session).
    ConsoleConnect(SessionId),
    /// The session stopped being the visible console session.
    ConsoleDisconnect(SessionId),
    /// An RDP client connected to this session.
    RemoteConnect(SessionId),
    /// An RDP client disconnected from this session (session keeps running).
    RemoteDisconnect(SessionId),
    /// The device is about to suspend (sleep/hibernate).
    Suspend,
    /// The device has resumed from suspend.
    Resume,
}

/// Why a usage session ended, persisted alongside the record for later
/// policy/audit purposes.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum EndReason {
    Lock,
    Logoff,
    Suspend,
    ConsoleDisconnect,
    RemoteDisconnect,
    /// The agent restarted while a session was open and could not observe
    /// how it actually ended; the session is closed defensively at the
    /// point the agent noticed.
    AgentRestart,
    /// The agent (or the Windows service hosting it) shut down cleanly
    /// while a session was open.
    AgentShutdown,
}

/// A completed span of device usage.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct UsageSessionRecord {
    pub start: DateTime<Utc>,
    pub end: DateTime<Utc>,
    pub end_reason: EndReason,
}

/// Result of feeding a single event into the tracker.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Transition {
    /// The event did not change whether the device is in active use.
    NoChange,
    /// A new usage session began at `start`.
    Started { start: DateTime<Utc> },
    /// The open usage session ended.
    Ended(UsageSessionRecord),
}

impl Transition {
    pub fn is_ended(&self) -> bool {
        matches!(self, Transition::Ended(_))
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct SessionPresence {
    /// `false` while locked, disconnected from console, or RDP-disconnected.
    visible: bool,
}

/// Tracks per-session visibility plus device suspend state, and derives a
/// single "is this device currently in use" signal from them.
#[derive(Debug, Default)]
pub struct UsageTracker {
    sessions: HashMap<SessionId, SessionPresence>,
    suspended: bool,
    open_since: Option<DateTime<Utc>>,
}

impl UsageTracker {
    pub fn new() -> Self {
        Self::default()
    }

    /// Rebuilds a tracker that already has an open usage session in
    /// progress, e.g. when the agent restarts and recovers a dangling
    /// session from the store. `active_session_id` should be marked visible
    /// so a subsequent `Logoff`/`Lock` for it behaves normally.
    pub fn resume_open_session(started_at: DateTime<Utc>) -> Self {
        Self {
            sessions: HashMap::new(),
            suspended: false,
            open_since: Some(started_at),
        }
    }

    pub fn is_active(&self) -> bool {
        self.open_since.is_some()
    }

    /// Applies an event that occurred at `at` (UTC) and returns the
    /// resulting transition, if any.
    pub fn apply(&mut self, event: SessionEvent, at: DateTime<Utc>) -> Transition {
        match event {
            SessionEvent::Logon(id) | SessionEvent::Unlock(id)
            | SessionEvent::ConsoleConnect(id) | SessionEvent::RemoteConnect(id) => {
                self.sessions.insert(id, SessionPresence { visible: true });
                self.recompute(at, None)
            }
            SessionEvent::Lock(id) => {
                self.set_visible(id, false);
                self.recompute(at, Some(EndReason::Lock))
            }
            SessionEvent::ConsoleDisconnect(id) => {
                self.set_visible(id, false);
                self.recompute(at, Some(EndReason::ConsoleDisconnect))
            }
            SessionEvent::RemoteDisconnect(id) => {
                self.set_visible(id, false);
                self.recompute(at, Some(EndReason::RemoteDisconnect))
            }
            SessionEvent::Logoff(id) => {
                self.sessions.remove(&id);
                self.recompute(at, Some(EndReason::Logoff))
            }
            SessionEvent::Suspend => {
                self.suspended = true;
                self.recompute(at, Some(EndReason::Suspend))
            }
            SessionEvent::Resume => {
                self.suspended = false;
                self.recompute(at, None)
            }
        }
    }

    /// Forcibly closes any open session, used on clean agent shutdown or
    /// when recovering a dangling session left open by a prior crash.
    pub fn close(&mut self, at: DateTime<Utc>, reason: EndReason) -> Option<UsageSessionRecord> {
        self.open_since.take().map(|start| UsageSessionRecord {
            start,
            end: at,
            end_reason: reason,
        })
    }

    fn set_visible(&mut self, id: SessionId, visible: bool) {
        self.sessions
            .entry(id)
            .and_modify(|p| p.visible = visible)
            .or_insert(SessionPresence { visible });
    }

    fn any_visible(&self) -> bool {
        !self.suspended && self.sessions.values().any(|p| p.visible)
    }

    fn recompute(&mut self, at: DateTime<Utc>, end_reason_if_closing: Option<EndReason>) -> Transition {
        let should_be_active = self.any_visible();
        match (self.open_since, should_be_active) {
            (None, true) => {
                self.open_since = Some(at);
                Transition::Started { start: at }
            }
            (Some(start), false) => {
                self.open_since = None;
                Transition::Ended(UsageSessionRecord {
                    start,
                    end: at,
                    end_reason: end_reason_if_closing.unwrap_or(EndReason::Logoff),
                })
            }
            _ => Transition::NoChange,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeZone;

    const S1: SessionId = 1;
    const S2: SessionId = 2;

    fn t(seconds: i64) -> DateTime<Utc> {
        Utc.timestamp_opt(1_700_000_000 + seconds, 0).unwrap()
    }

    #[test]
    fn logon_starts_a_usage_session() {
        let mut tr = UsageTracker::new();
        assert_eq!(tr.apply(SessionEvent::Logon(S1), t(0)), Transition::Started { start: t(0) });
        assert!(tr.is_active());
    }

    #[test]
    fn lock_ends_the_usage_session() {
        let mut tr = UsageTracker::new();
        tr.apply(SessionEvent::Logon(S1), t(0));
        let outcome = tr.apply(SessionEvent::Lock(S1), t(100));
        assert_eq!(
            outcome,
            Transition::Ended(UsageSessionRecord { start: t(0), end: t(100), end_reason: EndReason::Lock })
        );
        assert!(!tr.is_active());
    }

    #[test]
    fn unlock_after_lock_starts_a_new_session_not_resuming_the_old_one() {
        let mut tr = UsageTracker::new();
        tr.apply(SessionEvent::Logon(S1), t(0));
        tr.apply(SessionEvent::Lock(S1), t(100));
        let outcome = tr.apply(SessionEvent::Unlock(S1), t(500));
        assert_eq!(outcome, Transition::Started { start: t(500) });
    }

    #[test]
    fn logoff_ends_session_with_logoff_reason() {
        let mut tr = UsageTracker::new();
        tr.apply(SessionEvent::Logon(S1), t(0));
        let outcome = tr.apply(SessionEvent::Logoff(S1), t(50));
        assert_eq!(
            outcome,
            Transition::Ended(UsageSessionRecord { start: t(0), end: t(50), end_reason: EndReason::Logoff })
        );
    }

    #[test]
    fn suspend_ends_session_and_resume_restarts_it_if_session_still_visible() {
        let mut tr = UsageTracker::new();
        tr.apply(SessionEvent::Logon(S1), t(0));
        let suspended = tr.apply(SessionEvent::Suspend, t(60));
        assert_eq!(
            suspended,
            Transition::Ended(UsageSessionRecord { start: t(0), end: t(60), end_reason: EndReason::Suspend })
        );
        // Session S1 never locked, so as soon as the device is no longer
        // suspended it counts as in-use again. In practice Windows almost
        // always locks the workstation on resume (if "require sign-in on
        // wakeup" is enabled), which arrives as a separate Lock event and
        // ends this new session right away — see the test below.
        let resumed = tr.apply(SessionEvent::Resume, t(3600));
        assert_eq!(resumed, Transition::Started { start: t(3600) });
    }

    #[test]
    fn resume_stays_inactive_if_session_had_locked_before_suspend() {
        let mut tr = UsageTracker::new();
        tr.apply(SessionEvent::Logon(S1), t(0));
        tr.apply(SessionEvent::Lock(S1), t(10));
        tr.apply(SessionEvent::Suspend, t(20));
        let resumed = tr.apply(SessionEvent::Resume, t(3600));
        assert_eq!(resumed, Transition::NoChange);
        assert!(!tr.is_active());
    }

    #[test]
    fn second_session_keeps_device_active_when_first_locks() {
        let mut tr = UsageTracker::new();
        tr.apply(SessionEvent::Logon(S1), t(0));
        tr.apply(SessionEvent::Logon(S2), t(1));
        let outcome = tr.apply(SessionEvent::Lock(S1), t(10));
        assert_eq!(outcome, Transition::NoChange, "S2 still visible, device still in use");
        assert!(tr.is_active());
    }

    #[test]
    fn remote_disconnect_pauses_and_remote_connect_resumes() {
        let mut tr = UsageTracker::new();
        tr.apply(SessionEvent::Logon(S1), t(0));
        let paused = tr.apply(SessionEvent::RemoteDisconnect(S1), t(30));
        assert_eq!(
            paused,
            Transition::Ended(UsageSessionRecord { start: t(0), end: t(30), end_reason: EndReason::RemoteDisconnect })
        );
        let resumed = tr.apply(SessionEvent::RemoteConnect(S1), t(90));
        assert_eq!(resumed, Transition::Started { start: t(90) });
    }

    #[test]
    fn redundant_events_do_not_double_start_or_double_end() {
        let mut tr = UsageTracker::new();
        assert_eq!(tr.apply(SessionEvent::Logon(S1), t(0)), Transition::Started { start: t(0) });
        assert_eq!(tr.apply(SessionEvent::Unlock(S1), t(1)), Transition::NoChange);
        assert!(tr.apply(SessionEvent::Lock(S1), t(2)).is_ended());
        assert_eq!(tr.apply(SessionEvent::Lock(S1), t(3)), Transition::NoChange);
    }

    #[test]
    fn agent_restart_recovers_dangling_open_session_and_can_close_it() {
        let mut tr = UsageTracker::resume_open_session(t(0));
        assert!(tr.is_active());
        let closed = tr.close(t(5), EndReason::AgentRestart);
        assert_eq!(
            closed,
            Some(UsageSessionRecord { start: t(0), end: t(5), end_reason: EndReason::AgentRestart })
        );
        assert!(!tr.is_active());
    }
}
