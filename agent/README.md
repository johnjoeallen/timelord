# TimeLord Agent

Windows service (Rust) that tracks and, eventually, enforces device usage policy.

## Current scope

This is the first slice of the agent: it does **not** talk to a controller yet and
performs **no enforcement**. It only tracks *usage sessions* — contiguous spans of time
during which the device was actually being used — and persists them locally.

### Why usage tracking isn't just "uptime"

A service running as `LocalSystem` lives in Windows Session 0, isolated from the
interactive desktop, so it can't just poll keyboard/mouse activity. Instead it relies on
real-time OS notifications:

- **`WTSRegisterSessionNotification`** (registered with `NOTIFY_FOR_ALL_SESSIONS`) delivers
  `WM_WTSSESSION_CHANGE` for logon, logoff, lock, unlock, console connect/disconnect, and
  RDP connect/disconnect — for every session, even from Session 0.
- **`RegisterSuspendResumeNotification`** delivers suspend/resume via `WM_POWERBROADCAST`,
  so sleeping time isn't counted as usage.
- True idle-while-unlocked detection (`GetLastInputInfo`) is per-desktop and unreachable
  from a Session-0 service. That needs a small per-user companion process reporting idle
  time back over a local IPC channel — not implemented yet; see `src/platform/windows.rs`
  for where that would hook in.

A usage session is therefore defined as: **logged on, unlocked, visible (console or RDP),
and not suspended**. See `src/tracker.rs` for the state machine and its unit tests, which
are the best documentation of the exact semantics (e.g. a second session keeps the device
"active" while a first one locks; resume alone only restarts a session if it never locked
before suspend).

## Layout

```text
src/
├── tracker.rs        Platform-independent usage-session state machine (unit tested)
├── store.rs           SQLite persistence for usage_session records
├── lib.rs              UsageRecorder: wires the tracker to the store
├── platform/
│   ├── windows.rs      Real backend: WTS + power notifications (cfg(windows))
│   └── dev.rs           Interactive stdin REPL backend for non-Windows dev machines
├── service_win.rs     Windows Service Control Manager integration (cfg(windows))
└── main.rs              Entry point: SCM service on Windows, console REPL elsewhere
```

## Running locally (non-Windows dev machine)

```console
$ TIMELORD_DATA_DIR=/tmp/timelord-dev cargo run
timelord-agent> logon
timelord-agent> lock
timelord-agent> unlock
timelord-agent> status
active: true
timelord-agent> quit
```

Each command feeds a `SessionEvent` into the same tracker/store the real Windows backend
uses, so session start/end/duration logic can be exercised without a Windows host.

## Testing

```console
$ cargo test
```

The `windows.rs` backend can't be *run* on Linux, but it's cross-target type-checked as
part of development:

```console
$ rustup target add x86_64-pc-windows-gnu
$ cargo check --target x86_64-pc-windows-gnu
```

## Running on Windows

```console
> cargo run --release -- --console   # foreground, for manual testing
```

To install as a real service (`SERVICE_NAME = "TimeLordAgent"`, `LocalSystem`, auto-restart
on failure), build a release zip or grab one from GitHub Releases and run
`deployment/windows/install.ps1` — see `deployment/windows/README.md`.

## Data

By default the store lives at `C:\ProgramData\TimeLord\state.db` (override with
`TIMELORD_DATA_DIR`). If the agent is killed rather than shut down cleanly, the next start
finds the dangling open session and closes it with `end_reason = AGENT_RESTART` rather than
losing it.
