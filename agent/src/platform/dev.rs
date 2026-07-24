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
        let event = match cmd {
            "" => continue,
            "help" => {
                print_help();
                continue;
            }
            "quit" | "exit" => break,
            "logon" => SessionEvent::Logon(SESSION_ID),
            "logoff" => SessionEvent::Logoff(SESSION_ID),
            "lock" => SessionEvent::Lock(SESSION_ID),
            "unlock" => SessionEvent::Unlock(SESSION_ID),
            "console-connect" => SessionEvent::ConsoleConnect(SESSION_ID),
            "console-disconnect" => SessionEvent::ConsoleDisconnect(SESSION_ID),
            "remote-connect" => SessionEvent::RemoteConnect(SESSION_ID),
            "remote-disconnect" => SessionEvent::RemoteDisconnect(SESSION_ID),
            "suspend" => SessionEvent::Suspend,
            "resume" => SessionEvent::Resume,
            "status" => {
                println!("active: {}", recorder.is_active());
                continue;
            }
            other => {
                println!("unrecognised command: {other:?} (try 'help')");
                continue;
            }
        };
        recorder.handle(event)?;
    }

    recorder.shutdown()?;
    Ok(())
}

fn print_help() {
    println!(
        "commands: logon logoff lock unlock console-connect console-disconnect \
         remote-connect remote-disconnect suspend resume status quit"
    );
}
