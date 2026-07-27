# TimeLord Agent

Windows service (Rust) that tracks device usage and reports it to a TimeLord Controller.

> Phase 1: unauthenticated plain HTTP to the controller. The controller itself has no
> schedule/policy enforcement yet — the only enforcement today is the agent's own offline
> fallback (below), which acts locally when it can't reach the controller at all. See
> `../docs/architecture.md` for the security boundary.

## What it does

1. Tracks **usage sessions** locally — contiguous spans of time the device was actually
   being used, not just powered on (see [Usage tracking](#usage-tracking) below).
2. Finds a controller: an explicit `controller.url`, or active UDP discovery.
3. Registers, sends periodic heartbeats, and reports events (`AGENT_STARTED`, `USER_LOGON`,
   `USER_LOCK`, ... — the full list is in `src/protocol.rs::EventType`).
4. Queues events locally (SQLite) so a controller outage never loses one — see
   `../docs/architecture.md#event-delivery-and-idempotency`.

## CLI

```text
timelord-agent run                    Foreground, for development/debugging
timelord-agent service                Via the Windows SCM (what install registers)
timelord-agent install                Register + start the Windows service
timelord-agent uninstall              Stop + remove the Windows service
timelord-agent status                 Print resolved config + local queue size
timelord-agent discover [--force]     Run UDP discovery once, print results
                                       (--force also persists the winner to agent.toml)
timelord-agent test-event             Queue + attempt to deliver one synthetic event
```

`run` accepts `--controller-url`, `--device-id`, `--device-name`, `--no-discovery` to override
`agent.toml` for that invocation only (see [Configuration](#configuration)).

## Configuration

`C:\ProgramData\TimeLord\agent.toml` (override the directory with `TIMELORD_DATA_DIR`):

```toml
[agent]
device_id = ""                       # generated + persisted on first run if empty
device_name = ""                     # defaults to the hostname if empty
heartbeat_interval_seconds = 30
event_retry_interval_seconds = 10
offline_fallback_enabled = true      # sleep the device on the schedule below when offline
offline_fallback_start = "01:00"     # "HH:MM", local time; invalid/missing falls back to this default
offline_fallback_end = "08:30"

[controller]
url = ""                             # e.g. "http://192.168.1.20:8080"
discovery_enabled = true
discovery_port = 45821
discovery_timeout_seconds = 5
```

### Offline fallback

If the agent can't reach the controller at all (registration, heartbeats, and event delivery are
all failing), it falls back to a local safety schedule: every 60 seconds, if still disconnected
and local time falls within `offline_fallback_start`-`offline_fallback_end` (wrapping past
midnight is supported, e.g. `22:00`-`06:00`), it puts the device to sleep (`SetSuspendState`, not
hibernate). This only runs while disconnected — once the controller is reachable again this stops
immediately. `POWER_ACTION_REQUESTED`/`_COMPLETED`/`_FAILED` events are queued locally and
delivered once connectivity returns, so the outage and the fallback action it triggered are both
visible on the dashboard afterward.

Precedence is **CLI flags > environment variables > `agent.toml` > defaults**
(`src/config.rs::resolve`, unit tested for every combination). Environment overrides:

```text
TIMELORD_CONTROLLER_URL
TIMELORD_DEVICE_ID
TIMELORD_DEVICE_NAME
TIMELORD_DISCOVERY_ENABLED
```

A discovered controller URL is persisted back to `agent.toml`; a manually-configured URL is
never silently overwritten (only `discover --force` does that, since running it is itself the
explicit request).

## Usage tracking

A service running as `LocalSystem` lives in Windows Session 0, isolated from the interactive
desktop, so it can't just poll keyboard/mouse activity. Instead it relies on real-time OS
notifications:

- **`WTSRegisterSessionNotification`** (registered with `NOTIFY_FOR_ALL_SESSIONS`) delivers
  `WM_WTSSESSION_CHANGE` for logon, logoff, lock, unlock, console connect/disconnect, and
  RDP connect/disconnect — for every session, even from Session 0.
- **`RegisterSuspendResumeNotification`** delivers suspend/resume via `WM_POWERBROADCAST`,
  so sleeping time isn't counted as usage.
- True idle-while-unlocked detection (`GetLastInputInfo`) is per-desktop and unreachable
  from a Session-0 service. That needs a small per-user companion process reporting idle
  time back over a local IPC channel — not implemented yet; heartbeats currently send
  `idleSeconds: null`.

A usage session is therefore: **logged on, unlocked, visible (console or RDP), and not
suspended**. See `src/tracker.rs` for the state machine and its unit tests — the best
documentation of the exact semantics (e.g. a second session keeps the device "active" while
a first one locks; resume alone only restarts a session if it never locked before suspend).

Every tracker notification also becomes a reported event (`src/events.rs::from_session_event`)
— `USER_LOGON`, `USER_LOCK`, `SYSTEM_SUSPEND`, etc. — independent of whether it changed local
usage-session state.

## Layout

```text
src/
├── tracker.rs           Platform-independent usage-session state machine (unit tested)
├── store.rs              SQLite persistence for usage_session records
├── lib.rs                 UsageRecorder: wires tracker -> store, and -> EventQueue
├── config.rs               agent.toml + env + CLI precedence (unit tested)
├── protocol.rs              Wire DTOs matching the controller's JSON exactly (unit tested)
├── discovery.rs              UDP discovery client: request/validate/select (unit tested)
├── controller_client.rs       reqwest HTTP client (register/heartbeat/events/system-info)
├── queue.rs                    Local SQLite event queue + backoff (unit tested)
├── events.rs                    Tracker events & lifecycle moments -> protocol::EventType
├── agent.rs                      Ties it all together: networking loop, run_blocking()
├── platform/
│   ├── windows.rs                Real backend: WTS + power notifications (cfg(windows))
│   └── dev.rs                     Interactive stdin REPL backend for non-Windows dev machines
├── service_win.rs                Windows SCM integration: run/install/uninstall (cfg(windows))
└── main.rs                        CLI (clap) entry point
```

## Running locally (any OS)

```console
$ TIMELORD_DATA_DIR=/tmp/timelord-dev TIMELORD_CONTROLLER_URL=http://localhost:8080 cargo run -- run
timelord-agent> logon
timelord-agent> lock
timelord-agent> unlock
timelord-agent> status
active: true
timelord-agent> quit
```

Each command feeds a `SessionEvent` into the same tracker/store/queue the real Windows backend
uses, so the full pipeline — including registration, heartbeats, and event delivery against a
real controller — can be exercised without a Windows host. Point `TIMELORD_CONTROLLER_URL` at a
`docker compose up` controller (see root `README.md`) to see it end to end.

## Testing

```console
$ cargo test      # 59+ unit tests: tracker, store, config, protocol, discovery, queue, events
$ cargo clippy --all-targets
```

The `windows.rs`/`service_win.rs` backends can't be *run* on Linux, but they're cross-target
type-checked as part of development:

```console
$ rustup target add x86_64-pc-windows-gnu
$ cargo check --target x86_64-pc-windows-gnu --bins
```

## Running on Windows

```console
> cargo run --release -- run       # foreground, for manual testing
> timelord-agent.exe install       # register as a LocalSystem service and start it
> timelord-agent.exe status
> timelord-agent.exe uninstall
```

`install`/`uninstall` use the Service Control Manager API directly (`windows-service` crate),
not `sc.exe`. For a packaged zip with a PowerShell installer instead, see
`deployment/windows/README.md`.

## Data

By default state lives under `C:\ProgramData\TimeLord\` (override with `TIMELORD_DATA_DIR`):

```text
state.db     Usage sessions (tracker.rs / store.rs)
events.db    Local event queue pending controller delivery (queue.rs)
agent.toml   Configuration
logs/        Daily-rolling structured logs
```

If the agent is killed rather than shut down cleanly, the next start finds any dangling open
usage session and closes it with `end_reason = AGENT_RESTART`, and resumes delivering whatever
was still queued in `events.db` — nothing is lost.
