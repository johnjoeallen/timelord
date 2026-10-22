//! TimeLord notifier. Polls the agent service's local pipe and warns the
//! logged-on user before the device is powered off, and shows administrator
//! messages. Decisions live in `engine`, wording in `text`, display in `ui`.
//! Still to come: the last-minute countdown window and the tray icon.

mod engine;
mod state;
mod text;
mod transport;
mod ui;

use std::path::Path;
use std::thread;
use std::time::Duration;

use chrono::{Local, Utc};
use engine::{Engine, MessageTracker};
use timelord_ipc::{Request, Response};

fn main() {
    if !ui::init() {
        // Another notifier already runs in this session; a second would double every warning.
        return;
    }
    let state_path = state::default_path();
    let mut engine = Engine::default();
    let mut tracker = MessageTracker::with_shown(state::load_shown(&state_path));
    let mut service_was_down = false;
    loop {
        show_new_messages(&mut tracker, &state_path);
        let wait = match transport::call(&Request::GetPending) {
            Ok(Response::Pending { server_utc, actions }) => {
                service_was_down = false;
                // Judge remaining time on the service's clock; only display in local time.
                let skew = Utc::now() - server_utc;
                let now = Utc::now() - skew;
                let tick = engine.update(&actions, now);
                for id in &tick.cancelled {
                    ui::dismiss_warning(*id);
                }
                for due in &tick.toasts {
                    let at_local = due.action.at_utc.with_timezone(&Local).format("%H:%M").to_string();
                    ui::show_warning(due.action.id, &text::warning(due, &at_local));
                }
                tick.next_poll.unwrap_or(Duration::from_secs(10))
            }
            Ok(_) => Duration::from_secs(10),
            Err(err) => {
                // Service not running (or restarting): stay quiet and keep trying.
                if !service_was_down {
                    eprintln!("service unavailable: {err}");
                    service_was_down = true;
                }
                Duration::from_secs(10)
            }
        };
        thread::sleep(wait);
    }
}

/// Asks the service for administrator messages and shows any not yet shown, remembering them on disk.
fn show_new_messages(tracker: &mut MessageTracker, state_path: &Path) {
    let Ok(Response::Messages { server_utc, messages }) = transport::call(&Request::GetMessages) else {
        return;
    };
    let before = tracker.shown_ids();
    for message in tracker.take_new(&messages, server_utc) {
        ui::show_message(&message, &text::message(message.title.as_deref(), &message.body));
    }
    let after = tracker.shown_ids();
    if after != before {
        if let Err(err) = state::save_shown(state_path, &after) {
            eprintln!("could not save shown messages: {err}");
        }
    }
}
