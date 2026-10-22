# Agent

Windows service (Rust) that tracks device usage and reports it to a TimeLord Controller.

!!! note
    Unauthenticated plain HTTP to the controller. There is no session-limit or break enforcement
    yet. The agent does enforce a [schedule](scheduling.md)
    assigned by the controller (sleep or shutdown at off times); a device with no schedule is
    never put to sleep or shut down. See
    [Security boundary](architecture.md#security-boundary).

## What it does

1. Tracks **usage sessions** locally — contiguous spans of time the device was actually being
   used, not just powered on (see [Usage tracking](#usage-tracking) below).
2. Finds a controller: an explicit `controller.url`, or active UDP discovery.
3. Registers, sends periodic heartbeats, and reports events (`AGENT_STARTED`, `USER_LOGON`,
   `USER_LOCK`, ... — the full list is in `protocol.rs::EventType`).
4. Queues events locally (SQLite) so a controller outage never loses one — see
   [Event delivery and idempotency](architecture.md#event-delivery-and-idempotency).
5. Receives its [schedule](scheduling.md) as concrete off windows (in heartbeat responses and via
   a long poll that delivers changes within moments), caches it in `schedule.json`, and puts the
   device to sleep or shuts it down inside an off window — also while the controller is
   unreachable. At startup and after a sleep it first waits for the controller to confirm the
   cached schedule (up to 90 seconds), since a schedule edit or extension may have happened
   meanwhile; see [Startup and resume](scheduling.md#how-the-device-enforces-it).
6. Long-polls for administrator messages, caches them in `messages.json`, and serves pending
   power actions and messages to the per-user [notifier](notifier.md) over a local named pipe
   (`\\.\pipe\timelord-notify`, read-only).

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

[controller]
url = ""                             # e.g. "http://192.168.1.20:8080"
discovery_enabled = true
discovery_port = 45821
discovery_timeout_seconds = 5

[time]
ntp_enabled = true                   # check the system clock against NTP; schedules use NTP time
ntp_servers = ["time.cloudflare.com", "time.windows.com", "pool.ntp.org"]   # the defaults
```

### The clock

A schedule is a list of absolute UTC times, so it is only as right as the time the agent compares it
with, and Windows lets a user change the system clock. The agent therefore doesn't trust it: it
measures the system clock against **NTP servers** (SNTP over UDP port 123) and enforces schedules,
the notifier's countdowns and message expiry against *NTP-corrected time*.

- It measures at startup, every 15 minutes, after any sleep, and whenever it sees the system clock
  jump (the system clock stops keeping pace with the agent's own timer). The median of the servers
  that answer is used, so one wrong server can't move it.
- A system clock more than 30 seconds out is logged as a warning, for example *the system clock is
  behind the real time; schedules use NTP time*, with the offset in seconds.
- At startup a cached schedule isn't enforced until the clock has been checked as well as the
  schedule confirmed by the controller, or until the 90-second grace period passes if NTP can't be
  reached.
- If NTP becomes unreachable the last measured offset is kept, so blocking NTP only freezes the
  last measurement; a clock changed afterwards is still caught by the jump check once NTP is
  reachable again. The agent needs outbound UDP 123 (or your own server in `ntp_servers`).
- `ntp_enabled = false` switches this off and uses the system clock. `timelord-agent status` shows
  which servers are configured.

Event timestamps sent to the controller still use the system clock.

### Devices without a schedule

A device with no [schedule](scheduling.md) assigned is **unrestricted**: the agent never puts it to
sleep or shuts it down. Earlier versions slept such a device 02:00–06:00 and, while the controller
was unreachable, 01:00–08:30. Both are gone, and any `offline_fallback_*` keys left in `agent.toml`
are ignored.

An assigned schedule keeps being enforced from the agent's cached copy while the controller is
unreachable, so losing the network doesn't lift it.

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
`SYSTEM_SUSPEND`, etc.) independent of whether it changed local usage-session state. Derived
activity transitions are separate first-class `ACTIVE`/`INACTIVE` events, so a real logon may
appear as `USER_LOGON`, `ACTIVE`; startup reconciliation of an already logged-in session does
not create a synthetic logon.

## Data

By default state lives under `C:\ProgramData\TimeLord\` (override with `TIMELORD_DATA_DIR`):

```text
state.db        Usage sessions
events.db       Local event queue pending controller delivery
agent.toml      Configuration
schedule.json   Cached schedule (resolved off windows); absent if no schedule
messages.json   Cached administrator messages and the polling cursor
logs/           Daily-rolling structured logs
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
with a PowerShell installer is attached to every tagged release on GitHub. To install from it,
extract the zip and run, from PowerShell **as Administrator** in that folder:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\install.ps1
```

!!! note "Why the first line"
    Windows blocks PowerShell scripts that came from a download by default. `-Scope Process` lifts
    that for the current PowerShell window only; nothing is changed permanently, and it reverts when
    you close the window. Alternatively run `Unblock-File .\install.ps1, .\uninstall.ps1` (or untick
    *Unblock* in the zip's Properties before extracting). Re-running `install.ps1` with a newer
    `timelord-agent.exe` upgrades an existing install.

The zip contains both
`timelord-agent.exe` and `timelord-notifier.exe`, and `install.ps1` also registers a
**TimeLord Notifier** scheduled task that starts the [notifier](notifier.md) at each user's logon
(`-SkipNotifier` to skip it).
