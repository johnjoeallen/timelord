//! Read-only local pipe the per-user notifier polls for upcoming power
//! actions (see the `timelord-ipc` crate for the wire types).
//!
//! The service holds no notification state: every request recomputes the
//! answer from the currently cached [`Schedule`], so an extension or a
//! calendar change is reflected on the notifier's very next poll.

use chrono::{DateTime, Duration, Utc};
use timelord_ipc::{Action, Message, PendingAction, Reason, Request, Response, HORIZON_SECONDS};
use uuid::Uuid;

use crate::protocol::AgentMessage;
use crate::schedule::{PowerAction, Schedule};

/// Namespace for the deterministic action ids below.
const ID_NAMESPACE: Uuid = Uuid::from_u128(0x7b0c_3f4e_5a1d_4c8e_9a52_1e6d_4f7a_b301);

/// Actions starting within the next [`HORIZON_SECONDS`]. A window already
/// in progress is not "pending": the action has happened.
pub fn upcoming_actions(schedule: Option<&Schedule>, now: DateTime<Utc>) -> Vec<PendingAction> {
    let Some(schedule) = schedule else {
        return Vec::new();
    };
    let horizon = now + Duration::seconds(HORIZON_SECONDS);
    let action = match schedule.action {
        PowerAction::Sleep => Action::Sleep,
        PowerAction::Shutdown => Action::Shutdown,
    };
    schedule
        .windows
        .iter()
        .filter(|w| w.off_at > now && w.off_at <= horizon)
        .map(|w| PendingAction {
            id: Uuid::new_v5(&ID_NAMESPACE, format!("{action:?}@{}", w.off_at.to_rfc3339()).as_bytes()),
            action,
            at_utc: w.off_at,
            reason: Reason::Schedule,
        })
        .collect()
}

/// Answers one request line, or `None` for input that isn't a valid request
/// (ignored, like an unknown message type from a newer notifier).
pub fn handle_line(line: &str, schedule: Option<&Schedule>, messages: &[AgentMessage], now: DateTime<Utc>) -> Option<String> {
    let response = match serde_json::from_str::<Request>(line).ok()? {
        Request::GetPending => Response::Pending { server_utc: now, actions: upcoming_actions(schedule, now) },
        Request::GetMessages => Response::Messages {
            server_utc: now,
            messages: messages
                .iter()
                .filter(|m| m.expires_at > now)
                .map(|m| Message {
                    id: m.id,
                    title: m.title.clone(),
                    body: m.body.clone(),
                    sent_utc: m.created_at,
                    expires_utc: m.expires_at,
                })
                .collect(),
        },
    };
    serde_json::to_string(&response).ok()
}

#[cfg(windows)]
pub use self::server::serve;

/// Pipe transport only exists on Windows; elsewhere there is nothing to serve.
#[cfg(not(windows))]
pub async fn serve(_agent: std::sync::Arc<crate::agent::Agent>) {}

#[cfg(windows)]
mod server {
    use std::ffi::c_void;
    use std::io;
    use std::sync::Arc;

    use chrono::Utc;
    use timelord_ipc::PIPE_NAME;
    use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
    use tokio::net::windows::named_pipe::{NamedPipeServer, ServerOptions};
    use tracing::{error, info, warn};
    use windows::core::w;
    use windows::Win32::Foundation::{LocalFree, HLOCAL};
    use windows::Win32::Security::Authorization::{
        ConvertStringSecurityDescriptorToSecurityDescriptorW, SDDL_REVISION_1,
    };
    use windows::Win32::Security::{PSECURITY_DESCRIPTOR, SECURITY_ATTRIBUTES};

    use super::handle_line;
    use crate::agent::Agent;

    /// DACL: SYSTEM and Administrators full control; any authenticated user
    /// may read and write *data* (0x12008b = FILE_READ_DATA | FILE_WRITE_DATA
    /// | FILE_READ_ATTRIBUTES | FILE_READ_EA | READ_CONTROL | SYNCHRONIZE).
    /// Deliberately without FILE_CREATE_PIPE_INSTANCE (== FILE_APPEND_DATA),
    /// so a user can talk to the service's pipe but cannot stand up a second
    /// instance of it and impersonate the service to other users. The default
    /// pipe DACL only gives Everyone *read*, which is not enough to send a
    /// request.
    const SDDL: windows::core::PCWSTR = w!("D:(A;;0x12008b;;;AU)(A;;GA;;;SY)(A;;GA;;;BA)");

    fn create(first: bool) -> io::Result<NamedPipeServer> {
        let mut descriptor = PSECURITY_DESCRIPTOR::default();
        // SAFETY: SDDL is a valid NUL-terminated string; on success the OS
        // allocates `descriptor`, which is freed below after the pipe copies it.
        unsafe { ConvertStringSecurityDescriptorToSecurityDescriptorW(SDDL, SDDL_REVISION_1, &mut descriptor, None) }
            .map_err(|e| io::Error::other(e.to_string()))?;
        let mut attributes = SECURITY_ATTRIBUTES {
            nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32,
            lpSecurityDescriptor: descriptor.0,
            bInheritHandle: false.into(),
        };
        // SAFETY: `attributes` and the descriptor it points to outlive this call.
        let result = unsafe {
            ServerOptions::new()
                .first_pipe_instance(first)
                .reject_remote_clients(true)
                .create_with_security_attributes_raw(PIPE_NAME, &mut attributes as *mut _ as *mut c_void)
        };
        // SAFETY: allocated by the conversion call above and not used again.
        unsafe {
            let _ = LocalFree(HLOCAL(descriptor.0));
        }
        result
    }

    /// Accepts notifier connections forever. Always creates the next pipe
    /// instance before handing the connected one off, so there is never a
    /// moment with no listener.
    pub async fn serve(agent: Arc<Agent>) {
        let mut server = match create(true) {
            Ok(server) => server,
            Err(err) => {
                error!(?err, "could not create notifier pipe; notifications unavailable");
                return;
            }
        };
        info!(pipe = PIPE_NAME, "notifier pipe listening");
        loop {
            if let Err(err) = server.connect().await {
                warn!(?err, "notifier pipe connect failed");
                tokio::time::sleep(std::time::Duration::from_secs(1)).await;
            }
            let client = server;
            server = match create(false) {
                Ok(next) => next,
                Err(err) => {
                    error!(?err, "could not create next notifier pipe instance; notifications unavailable");
                    return;
                }
            };

            let agent = agent.clone();
            tokio::spawn(async move {
                let (reader, mut writer) = tokio::io::split(client);
                let mut lines = BufReader::new(reader).lines();
                while let Ok(Some(line)) = lines.next_line().await {
                    let Some(mut out) = handle_line(&line, agent.schedule().as_ref(), &agent.live_messages(), Utc::now()).map(String::into_bytes)
                    else {
                        continue;
                    };
                    out.push(b'\n');
                    if writer.write_all(&out).await.is_err() {
                        break;
                    }
                }
            });
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::schedule::OffWindow;
    use chrono::TimeZone;

    fn at(h: u32, m: u32) -> DateTime<Utc> {
        Utc.with_ymd_and_hms(2026, 10, 26, h, m, 0).unwrap()
    }

    fn schedule(action: PowerAction, windows: Vec<OffWindow>) -> Schedule {
        Schedule { revision: "r".into(), action, timezone: "UTC".into(), windows }
    }

    fn window(off: DateTime<Utc>) -> OffWindow {
        OffWindow { off_at: off, on_at: off + Duration::hours(8) }
    }

    #[test]
    fn only_actions_inside_the_horizon_are_pending() {
        let s = schedule(PowerAction::Sleep, vec![window(at(22, 30)), window(at(23, 45))]);

        let pending = upcoming_actions(Some(&s), at(22, 10));

        assert_eq!(pending.len(), 1);
        assert_eq!(pending[0].at_utc, at(22, 30));
        assert_eq!(pending[0].action, Action::Sleep);
        assert_eq!(pending[0].reason, Reason::Schedule);
        assert!(upcoming_actions(Some(&s), at(12, 0)).is_empty());
    }

    #[test]
    fn a_window_already_in_progress_is_not_pending() {
        let s = schedule(PowerAction::Shutdown, vec![window(at(22, 30))]);
        assert!(upcoming_actions(Some(&s), at(22, 31)).is_empty());
    }

    #[test]
    fn no_schedule_means_nothing_pending() {
        assert!(upcoming_actions(None, at(22, 0)).is_empty());
    }

    #[test]
    fn id_is_stable_per_time_and_changes_when_extended() {
        let a = upcoming_actions(Some(&schedule(PowerAction::Sleep, vec![window(at(22, 30))])), at(22, 0));
        let again = upcoming_actions(Some(&schedule(PowerAction::Sleep, vec![window(at(22, 30))])), at(22, 5));
        let extended = upcoming_actions(Some(&schedule(PowerAction::Sleep, vec![window(at(22, 34))])), at(22, 5));

        assert_eq!(a[0].id, again[0].id);
        assert_ne!(a[0].id, extended[0].id);
    }

    #[test]
    fn handle_line_answers_get_pending_and_ignores_garbage() {
        let s = schedule(PowerAction::Sleep, vec![window(at(22, 30))]);

        let reply = handle_line(r#"{"type":"GetPending"}"#, Some(&s), &[], at(22, 20)).unwrap();
        let Response::Pending { server_utc, actions } = serde_json::from_str(&reply).unwrap() else { panic!() };
        assert_eq!(server_utc, at(22, 20));
        assert_eq!(actions.len(), 1);

        assert!(handle_line("not json", Some(&s), &[], at(22, 20)).is_none());
        assert!(handle_line(r#"{"type":"Postpone"}"#, Some(&s), &[], at(22, 20)).is_none());
    }

    #[test]
    fn get_messages_returns_only_unexpired_messages() {
        let live = AgentMessage {
            seq: 1,
            id: Uuid::from_u128(1),
            title: Some("Hi".into()),
            body: "Dinner".into(),
            created_at: at(22, 0),
            expires_at: at(23, 0),
        };
        let stale = AgentMessage { seq: 2, id: Uuid::from_u128(2), expires_at: at(21, 0), ..live.clone() };

        let reply = handle_line(r#"{"type":"GetMessages"}"#, None, &[live, stale], at(22, 20)).unwrap();

        let Response::Messages { messages, .. } = serde_json::from_str(&reply).unwrap() else { panic!() };
        assert_eq!(messages.len(), 1);
        assert_eq!(messages[0].body, "Dinner");
        assert_eq!(messages[0].title.as_deref(), Some("Hi"));
    }
}
