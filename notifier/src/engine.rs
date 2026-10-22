//! Pure decision logic: given what the service reports is pending and the
//! current time, which warnings are due. No I/O, no clock reads, so every
//! rule here is unit tested.

use std::collections::HashSet;
use std::time::Duration;

use chrono::{DateTime, Utc};
use timelord_ipc::{Message, PendingAction};
use uuid::Uuid;

/// Seconds-remaining marks at which to warn, widest first.
pub const THRESHOLDS: [i64; 4] = [900, 300, 60, 30];
/// Inside this many seconds the notifier shows a countdown and polls faster.
pub const COUNTDOWN_SECONDS: i64 = 60;
const FAST_POLL_WITHIN_SECONDS: i64 = 90;
const FAST_POLL: Duration = Duration::from_secs(2);
const SLOW_POLL: Duration = Duration::from_secs(10);

#[derive(Debug, Clone, PartialEq)]
pub struct ToastDue {
    pub action: PendingAction,
    /// The threshold this warning is for (seconds).
    pub threshold: i64,
    pub remaining: i64,
}

#[derive(Debug, Default, PartialEq)]
pub struct Tick {
    pub toasts: Vec<ToastDue>,
    /// Actions that were pending before and no longer are (cancelled, moved, or already happening):
    /// any toast shown for them should be dismissed.
    pub cancelled: Vec<Uuid>,
    /// Actions close enough that a live countdown should be showing.
    pub countdown: Vec<Uuid>,
    pub next_poll: Option<Duration>,
}

#[derive(Debug, Default)]
pub struct Engine {
    fired: HashSet<(Uuid, i64)>,
    known: HashSet<Uuid>,
}

impl Engine {
    /// `now` is the current time on the service's clock (the notifier derives it from the response's
    /// `serverUtc`, so a skewed user clock doesn't shift the warnings).
    pub fn update(&mut self, actions: &[PendingAction], now: DateTime<Utc>) -> Tick {
        let live: HashSet<Uuid> = actions.iter().map(|a| a.id).collect();
        let mut cancelled: Vec<Uuid> = self.known.difference(&live).copied().collect();
        cancelled.sort();
        self.fired.retain(|(id, _)| live.contains(id));
        self.known = live;

        let mut toasts = Vec::new();
        let mut countdown = Vec::new();
        let mut soonest: Option<i64> = None;
        for action in actions {
            let remaining = (action.at_utc - now).num_seconds();
            soonest = Some(soonest.map_or(remaining, |s| s.min(remaining)));

            // Only the tightest threshold already crossed: starting up with 3 minutes left
            // warns for 5 minutes, not also for 15, and a missed poll never replays old marks.
            if let Some(threshold) = THRESHOLDS.iter().copied().filter(|t| remaining <= *t).min() {
                if self.fired.insert((action.id, threshold)) {
                    toasts.push(ToastDue { action: action.clone(), threshold, remaining });
                }
            }
            if remaining <= COUNTDOWN_SECONDS {
                countdown.push(action.id);
            }
        }

        let next_poll = Some(match soonest {
            Some(s) if s <= FAST_POLL_WITHIN_SECONDS => FAST_POLL,
            _ => SLOW_POLL,
        });
        Tick { toasts, cancelled, countdown, next_poll }
    }
}

/// Remembers which administrator messages have been shown, so each appears once however many times
/// the service reports it.
#[derive(Debug, Default)]
pub struct MessageTracker {
    shown: HashSet<Uuid>,
}

impl MessageTracker {
    /// Restores previously shown ids (so a restart or a later logon doesn't repeat messages).
    pub fn with_shown(ids: impl IntoIterator<Item = Uuid>) -> Self {
        Self { shown: ids.into_iter().collect() }
    }

    pub fn shown_ids(&self) -> Vec<Uuid> {
        let mut ids: Vec<Uuid> = self.shown.iter().copied().collect();
        ids.sort();
        ids
    }

    /// The reported messages not yet shown and not expired, oldest first. Marks them shown.
    pub fn take_new(&mut self, reported: &[Message], now: DateTime<Utc>) -> Vec<Message> {
        let mut fresh: Vec<Message> =
            reported.iter().filter(|m| m.expires_utc > now && !self.shown.contains(&m.id)).cloned().collect();
        fresh.sort_by_key(|m| m.sent_utc);
        self.shown.extend(fresh.iter().map(|m| m.id));
        // Forget ids the service no longer reports (expired there), so this can't grow forever.
        let reported_ids: HashSet<Uuid> = reported.iter().map(|m| m.id).collect();
        self.shown.retain(|id| reported_ids.contains(id));
        fresh
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::{Duration as Dur, TimeZone};
    use timelord_ipc::{Action, Reason};

    fn t0() -> DateTime<Utc> {
        Utc.with_ymd_and_hms(2026, 10, 26, 22, 0, 0).unwrap()
    }

    fn action(n: u128, in_secs: i64) -> PendingAction {
        PendingAction {
            id: Uuid::from_u128(n),
            action: Action::Sleep,
            at_utc: t0() + Dur::seconds(in_secs),
            reason: Reason::Schedule,
        }
    }

    fn thresholds(tick: &Tick) -> Vec<i64> {
        tick.toasts.iter().map(|t| t.threshold).collect()
    }

    #[test]
    fn nothing_fires_before_the_widest_threshold() {
        let mut e = Engine::default();
        let tick = e.update(&[action(1, 1200)], t0());
        assert!(tick.toasts.is_empty());
        assert_eq!(tick.next_poll, Some(SLOW_POLL));
    }

    #[test]
    fn each_threshold_fires_once_as_time_passes() {
        let mut e = Engine::default();
        let a = action(1, 1000);
        let mut fired = Vec::new();
        for elapsed in [0, 100, 101, 400, 700, 800, 950, 951, 970, 999] {
            fired.extend(thresholds(&e.update(std::slice::from_ref(&a), t0() + Dur::seconds(elapsed))));
        }
        assert_eq!(fired, vec![900, 300, 60, 30]);
    }

    #[test]
    fn repeat_polls_in_the_same_band_do_not_retoast() {
        let mut e = Engine::default();
        let a = action(1, 800);
        assert_eq!(thresholds(&e.update(std::slice::from_ref(&a), t0())), vec![900]);
        assert!(e.update(std::slice::from_ref(&a), t0() + Dur::seconds(2)).toasts.is_empty());
        assert!(e.update(std::slice::from_ref(&a), t0() + Dur::seconds(4)).toasts.is_empty());
    }

    #[test]
    fn starting_late_fires_only_the_tightest_crossed_threshold() {
        let mut e = Engine::default();
        let tick = e.update(&[action(1, 180)], t0());
        assert_eq!(thresholds(&tick), vec![300]);
    }

    #[test]
    fn a_missed_poll_skips_straight_to_the_current_band() {
        let mut e = Engine::default();
        let a = action(1, 1000);
        assert_eq!(thresholds(&e.update(std::slice::from_ref(&a), t0() + Dur::seconds(150))), vec![900]);
        // Next poll only arrives with 40s left: warn for 60, not for 300.
        assert_eq!(thresholds(&e.update(std::slice::from_ref(&a), t0() + Dur::seconds(960))), vec![60]);
    }

    #[test]
    fn disappearing_action_is_reported_cancelled_and_forgotten() {
        let mut e = Engine::default();
        e.update(&[action(1, 800)], t0());

        let tick = e.update(&[], t0() + Dur::seconds(10));
        assert_eq!(tick.cancelled, vec![Uuid::from_u128(1)]);
        assert!(e.update(&[], t0() + Dur::seconds(20)).cancelled.is_empty());

        // The same id coming back later (e.g. extension undone) warns afresh.
        assert_eq!(thresholds(&e.update(&[action(1, 800)], t0() + Dur::seconds(30))), vec![900]);
    }

    #[test]
    fn extension_is_a_cancel_plus_a_new_action() {
        let mut e = Engine::default();
        e.update(&[action(1, 250)], t0());
        let tick = e.update(&[action(2, 280)], t0() + Dur::seconds(10));
        assert_eq!(tick.cancelled, vec![Uuid::from_u128(1)]);
        assert_eq!(thresholds(&tick), vec![300]);
    }

    #[test]
    fn countdown_and_fast_polling_start_inside_the_last_minute_and_a_half() {
        let mut e = Engine::default();
        assert_eq!(e.update(&[action(1, 100)], t0()).next_poll, Some(SLOW_POLL));
        let tick = e.update(&[action(1, 100)], t0() + Dur::seconds(15)); // 85s left
        assert_eq!(tick.next_poll, Some(FAST_POLL));
        assert!(tick.countdown.is_empty());
        let tick = e.update(&[action(1, 100)], t0() + Dur::seconds(45)); // 55s left
        assert_eq!(tick.countdown, vec![Uuid::from_u128(1)]);
    }

    #[test]
    fn independent_actions_track_separately() {
        let mut e = Engine::default();
        let tick = e.update(&[action(1, 250), action(2, 800)], t0());
        let mut got: Vec<_> = tick.toasts.iter().map(|t| (t.action.id, t.threshold)).collect();
        got.sort();
        assert_eq!(got, vec![(Uuid::from_u128(1), 300), (Uuid::from_u128(2), 900)]);
    }

    #[test]
    fn idle_with_nothing_pending_polls_slowly() {
        let mut e = Engine::default();
        assert_eq!(e.update(&[], t0()), Tick { next_poll: Some(SLOW_POLL), ..Tick::default() });
    }

    fn message(n: u128, sent_min: i64, expires_min: i64) -> Message {
        Message {
            id: Uuid::from_u128(n),
            title: None,
            body: format!("m{n}"),
            sent_utc: t0() + Dur::minutes(sent_min),
            expires_utc: t0() + Dur::minutes(expires_min),
        }
    }

    #[test]
    fn a_message_is_shown_once_even_if_reported_every_poll() {
        let mut t = MessageTracker::default();
        let m = message(1, 0, 60);
        assert_eq!(t.take_new(std::slice::from_ref(&m), t0()).len(), 1);
        assert!(t.take_new(std::slice::from_ref(&m), t0() + Dur::seconds(10)).is_empty());
    }

    #[test]
    fn new_messages_come_oldest_first_and_expired_ones_are_skipped() {
        let mut t = MessageTracker::default();
        let got = t.take_new(&[message(2, 5, 60), message(1, 1, 60), message(3, 0, -1)], t0());
        let order: Vec<u128> = got.iter().map(|m| m.id.as_u128()).collect();
        assert_eq!(order, vec![1, 2]);
    }

    #[test]
    fn restored_ids_are_not_shown_again() {
        let mut t = MessageTracker::with_shown([Uuid::from_u128(1)]);
        let got = t.take_new(&[message(1, 0, 60), message(2, 1, 60)], t0());
        assert_eq!(got.len(), 1);
        assert_eq!(got[0].id, Uuid::from_u128(2));
        assert_eq!(t.shown_ids(), vec![Uuid::from_u128(1), Uuid::from_u128(2)]);
    }

    #[test]
    fn later_messages_are_still_picked_up() {
        let mut t = MessageTracker::default();
        t.take_new(&[message(1, 0, 60)], t0());
        let got = t.take_new(&[message(1, 0, 60), message(2, 3, 60)], t0() + Dur::minutes(4));
        assert_eq!(got.len(), 1);
        assert_eq!(got[0].id, Uuid::from_u128(2));
    }
}
