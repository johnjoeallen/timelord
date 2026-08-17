//! Non-Windows development backend.
//!
//! There is no real session/power notification source on this platform, so
//! this reads simple commands from stdin and turns them into
//! [`SessionEvent`]s. It exists purely so the tracker + store can be
//! exercised end-to-end on the Linux/macOS box this is developed on, ahead
//! of the real `windows.rs` backend being run on an actual Windows host.

use std::io::{self, BufRead, Write};

use tracing::info;

use crate::tracker::SessionEvent;
use crate::UsageRecorder;

const SESSION_ID: u32 = 1;

/// Stand-in for the real Windows `SetSuspendState` call — this dev backend
/// has no way to actually suspend the box it's running on, so it just logs.
pub fn suspend() -> anyhow::Result<()> {
    info!("dev backend: pretending to suspend the device");
    Ok(())
}

pub fn run(mut recorder: UsageRecorder) -> anyhow::Result<()> {
    info!("dev backend active (not Windows) — type commands, 'help' for a list, 'quit' to exit");
    print_help();

    let stdin = io::stdin();
    loop {
        print!("timelord-agent> ");
        io::stdout().flush()?;

        let mut line = String::new();
        if stdin.lock().read_line(&mut line)? == 0 {
            break; // EOF
        }
        let cmd = line.trim();
        let mut parts = cmd.split_whitespace();
        let (event, username) = match parts.next().unwrap_or("") {
            "" => continue,
            "help" => {
                print_help();
                continue;
            }
            "quit" | "exit" => break,
            "logon" => (SessionEvent::Logon(SESSION_ID), Some(parts.next().unwrap_or("dev-user").to_string())),
            "logoff" => (SessionEvent::Logoff(SESSION_ID), None),
            "lock" => (SessionEvent::Lock(SESSION_ID), None),
            "unlock" => (SessionEvent::Unlock(SESSION_ID), None),
            "console-connect" => (SessionEvent::ConsoleConnect(SESSION_ID), None),
            "console-disconnect" => (SessionEvent::ConsoleDisconnect(SESSION_ID), None),
            "remote-connect" => (SessionEvent::RemoteConnect(SESSION_ID), None),
            "remote-disconnect" => (SessionEvent::RemoteDisconnect(SESSION_ID), None),
            "suspend" => (SessionEvent::Suspend, None),
            "resume" => (SessionEvent::Resume, None),
            "status" => {
                println!("active: {}", recorder.is_active());
                continue;
            }
            other => {
                println!("unrecognised command: {other:?} (try 'help')");
                continue;
            }
        };
        recorder.handle(event, username)?;
    }

    recorder.shutdown()?;
    Ok(())
}

fn print_help() {
    println!(
        "commands: logon [username] logoff lock unlock console-connect console-disconnect \
         remote-connect remote-disconnect suspend resume status quit"
    );
}
