# Agent

Windows service (Rust) that tracks device usage and reports it to a TimeLord Controller.

!!! note
    Phase 1: unauthenticated plain HTTP to the controller. The controller itself has no
    schedule/policy enforcement yet — the only enforcement today is the agent's own offline
    fallback (below), which acts locally when it can't reach the controller at all. See
    [Security boundary](architecture.md#security-boundary).

## What it does

1. Tracks **usage sessions** locally — contiguous spans of time the device was actually being
   used, not just powered on (see [Usage tracking](#usage-tracking) below).
2. Finds a controller: an explicit `controller.url`, or active UDP discovery.
3. Registers, sends periodic heartbeats, and reports events (`AGENT_STARTED`, `USER_LOGON`,
   `USER_LOCK`, ... — the full list is in `protocol.rs::EventType`).
4. Queues events locally (SQLite) so a controller outage never loses one — see
   [Event delivery and idempotency](architecture.md#event-delivery-and-idempotency).

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
all failing), it falls back to a local safety schedule: every 5 minutes, if still disconnected and
local time falls within `offline_fallback_start`-`offline_fallback_end` (wrapping past midnight is
supported, e.g. `22:00`-`06:00`), it puts the device to sleep (`SetSuspendState`, not hibernate — a
shutdown never happens). This only runs while disconnected — once the controller is reachable
again this stops immediately. `POWER_ACTION_REQUESTED`/`_COMPLETED`/`_FAILED` events are queued
locally and delivered once connectivity returns, so the outage and the fallback action it
triggered are both visible on the dashboard afterward.

!!! warning
    There is no way to configure this per-device from the controller/dashboard, or via
    environment variable/CLI flag, yet — the only way to change or disable it is hand-editing that
    device's local `agent.toml` (the `offline_fallback_*` keys above) and restarting the service.

Precedence is **CLI flags > environment variables > `agent.toml` > defaults**, unit tested for
every combination. Environment overrides:

```text
TIMELORD_CONTROLLER_URL
TIMELORD_DEVICE_ID
TIMELORD_DEVICE_NAME
TIMELORD_DISCOVERY_ENABLED
```

A discovered controller URL is persisted back to `agent.toml`; a manually-configured URL is never
silently overwritten (only `discover --force` does that, since running it is itself the explicit
request).

## Usage tracking

A service running as `LocalSystem` lives in Windows Session 0, isolated from the interactive
desktop, so it can't just poll keyboard/mouse activity. Instead it relies on real-time OS
notifications:

- **`WTSRegisterSessionNotification`** (registered with `NOTIFY_FOR_ALL_SESSIONS`) delivers
  `WM_WTSSESSION_CHANGE` for logon, logoff, lock, unlock, console connect/disconnect, and RDP
  connect/disconnect — for every session, even from Session 0.
- **`RegisterSuspendResumeNotification`** delivers suspend/resume via `WM_POWERBROADCAST`, so
  sleeping time isn't counted as usage.
- True idle-while-unlocked detection (`GetLastInputInfo`) is per-desktop and unreachable from a
  Session-0 service. That needs a small per-user companion process reporting idle time back over a
  local IPC channel — not implemented yet; heartbeats currently send `idleSeconds: null`.

A usage session is therefore: **logged on, unlocked, visible (console or RDP), and not
suspended**. A second session keeps the device "active" while a first one locks; resume alone only
restarts a session if it never locked before suspend.

Every tracker notification also becomes a reported event (`USER_LOGON`, `USER_LOCK`,
`SYSTEM_SUSPEND`, etc.) independent of whether it changed local usage-session state.

## Data

By default state lives under `C:\ProgramData\TimeLord\` (override with `TIMELORD_DATA_DIR`):

```text
state.db     Usage sessions
events.db    Local event queue pending controller delivery
agent.toml   Configuration
logs/        Daily-rolling structured logs
```

If the agent is killed rather than shut down cleanly, the next start finds any dangling open usage
session and closes it with `end_reason = AGENT_RESTART`, and resumes delivering whatever was still
queued in `events.db` — nothing is lost.

## Running on Windows

```console
> timelord-agent.exe install       # register as a LocalSystem service and start it
> timelord-agent.exe status
> timelord-agent.exe uninstall
```

`install`/`uninstall` use the Service Control Manager API directly, not `sc.exe`. A packaged zip
with a PowerShell installer is attached to every tagged release on GitHub.
