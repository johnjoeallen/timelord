//! Wording for warnings and messages. Pure so the phrasing and urgency rules
//! are unit tested without any UI.

use timelord_ipc::{Action, Reason};

use crate::engine::ToastDue;

#[derive(Debug, Clone, PartialEq)]
pub struct ToastText {
    pub title: String,
    pub body: String,
    /// Stays on screen until dismissed (the 1-minute and 30-second warnings).
    pub urgent: bool,
}

fn what(action: Action) -> &'static str {
    match action {
        Action::Shutdown => "shut down",
        Action::Restart => "restart",
        Action::Sleep => "go to sleep",
        Action::Logoff => "sign you out",
        Action::Lock => "lock",
        Action::BreakStart => "start a break",
    }
}

fn why(reason: Reason) -> &'static str {
    match reason {
        Reason::Schedule => "Scheduled",
        Reason::DailyLimit => "Daily limit reached",
        Reason::MandatoryBreak => "Time for a break",
        Reason::RemoteCommand => "Requested by an administrator",
    }
}

fn remaining_phrase(seconds: i64) -> String {
    let seconds = seconds.max(0);
    if seconds >= 120 {
        format!("in {} minutes", (seconds + 30) / 60)
    } else if seconds >= 60 {
        "in 1 minute".to_string()
    } else if seconds <= 1 {
        "in 1 second".to_string()
    } else {
        format!("in {seconds} seconds")
    }
}

/// `at_local` is the action time already formatted in the user's local time (e.g. "22:30").
pub fn warning(due: &ToastDue, at_local: &str) -> ToastText {
    ToastText {
        title: format!("This computer will {} {}", what(due.action.action), remaining_phrase(due.remaining)),
        body: format!("{}: {} at {at_local}. Save your work.", why(due.action.reason), what(due.action.action)),
        urgent: due.threshold <= 60,
    }
}

pub fn message(title: Option<&str>, body: &str) -> ToastText {
    ToastText { title: title.unwrap_or("TimeLord").to_string(), body: body.to_string(), urgent: false }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::{TimeZone, Utc};
    use timelord_ipc::PendingAction;
    use uuid::Uuid;

    fn due(action: Action, reason: Reason, threshold: i64, remaining: i64) -> ToastDue {
        ToastDue {
            action: PendingAction {
                id: Uuid::nil(),
                action,
                at_utc: Utc.with_ymd_and_hms(2026, 10, 26, 22, 30, 0).unwrap(),
                reason,
            },
            threshold,
            remaining,
        }
    }

    #[test]
    fn wording_follows_action_and_remaining_time() {
        let t = warning(&due(Action::Sleep, Reason::Schedule, 900, 897), "22:30");
        assert_eq!(t.title, "This computer will go to sleep in 15 minutes");
        assert_eq!(t.body, "Scheduled: go to sleep at 22:30. Save your work.");

        assert_eq!(warning(&due(Action::Shutdown, Reason::Schedule, 60, 75), "22:30").title, "This computer will shut down in 1 minute");
        assert_eq!(warning(&due(Action::Shutdown, Reason::Schedule, 30, 28), "22:30").title, "This computer will shut down in 28 seconds");
    }

    #[test]
    fn only_the_last_two_warnings_are_urgent() {
        let urgent = |th| warning(&due(Action::Sleep, Reason::Schedule, th, th), "22:30").urgent;
        assert_eq!([urgent(900), urgent(300), urgent(60), urgent(30)], [false, false, true, true]);
    }

    #[test]
    fn overdue_remaining_never_reads_negative() {
        assert_eq!(warning(&due(Action::Sleep, Reason::Schedule, 30, -3), "22:30").title, "This computer will go to sleep in 1 second");
    }

    #[test]
    fn message_defaults_its_title() {
        assert_eq!(message(None, "Dinner").title, "TimeLord");
        assert_eq!(message(Some("Mum"), "Dinner").title, "Mum");
    }
}
