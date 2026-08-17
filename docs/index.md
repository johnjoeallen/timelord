# TimeLord

TimeLord tracks how Windows devices on your network are actually being used — who's logged in,
for how long, and whether the machine is even reachable — and shows it on a live dashboard.

!!! danger "Developer preview — not for production use"
    This is Phase 1: a working proof of the system's shape, not a hardened product. Expect rough
    edges and breaking changes between versions. Three things worth knowing before you point it
    at real devices — details in [Before you deploy this](architecture.md#before-you-deploy-this):

    - **Monitoring only.** No schedule engine, session-limit enforcement, forced breaks, or
      remote power control — the project's tagline describes the eventual product, not what's
      running today. TimeLord **observes and reports**; it doesn't act.
    - **Every agent must reach the controller directly, on the same network(s).** Everything is
      unicast HTTP — no NAT traversal, relay, or cloud/VPN routing.
    - **Unreachable devices get put to sleep overnight.** By default, an agent that can't reach
      the controller *at all* puts the device to sleep between 01:00–08:30 local time — not
      configurable from the controller, only from that device's local `agent.toml`.

    It also runs over plain, unauthenticated HTTP — see
    [Security boundary](architecture.md#security-boundary) before running it anywhere but a
    trusted local network.

## What it actually does today

- **Windows agent** (Rust service) watches Windows session/power notifications — logon, lock,
  unlock, logoff, console/RDP connect and disconnect, suspend, resume — and the currently
  logged-in user (including one already logged in when the service starts), and reports them to
  a controller. Every event is queued to local disk before delivery, so a controller outage never
  loses data; queued events retry with backoff and flush immediately on reconnect or shutdown.
- **Controller** (Java / Spring Boot / PostgreSQL) accepts registration, heartbeats, and events
  over HTTP, and shows each device as one of three states:
    - **Offline** — no events received within the timeout window.
    - **Inactive** — online (agent reachable, recent heartbeats), but nobody's logged in.
    - **Active** — online and a user is actually logged in right now.
- **Login sessions** — a per-device history of login → logout spans, derived from the event log,
  distinct from "the agent was running." Starting the service with nobody logged in shows the
  machine online but produces no session at all.
- **Dashboard** — a server-rendered admin UI: device list with live status, per-device detail
  (network interfaces, current user, login history), and a searchable event log.

## Screenshots

<figure markdown>
  ![Dashboard showing three devices: Offline, Active, and Inactive](images/dashboard.png)
  <figcaption>The dashboard shows exactly one status badge per device: Offline, Active (online with
  someone logged in), or Inactive (online with nobody logged in).</figcaption>
</figure>

<figure markdown>
  ![Device detail page, Session tab, showing status, network interfaces, and current user](images/device-detail.png)
  <figcaption>Device detail — Session tab: live status, every network adapter (name/MAC/address),
  and who's currently logged in.</figcaption>
</figure>

<figure markdown>
  ![Device detail page, History tab, showing a table of past login sessions](images/device-detail-history.png)
  <figcaption>Device detail — History tab: every login session for this device, and how each one
  ended (logged out, disappeared, and so on).</figcaption>
</figure>

<figure markdown>
  ![Device detail page, Service tab, showing agent service state and registration times](images/device-detail-service.png)
  <figcaption>Device detail — Service tab: the agent service's own state — registration and
  heartbeat timestamps, independent of whether anyone's logged in.</figcaption>
</figure>

## Get started

TimeLord runs as two containers — `postgres` + `controller` — via `docker compose`:

```console
$ cp .env.example .env        # edit TIMELORD_PUBLIC_URL to your LAN IP
$ docker compose up --build   # postgres + controller on :9099, discovery UDP on :45821
```

Open `http://localhost:9099` for the dashboard, or:

```console
$ curl http://localhost:9099/api/v1/system/info
```

### Try the agent against it (from a dev machine, any OS)

```console
$ cd agent
$ TIMELORD_CONTROLLER_URL=http://localhost:9099 cargo run -- run
timelord-agent> logon
timelord-agent> lock
timelord-agent> quit
```

This registers a device, sends an `AGENT_STARTED`/`USER_LOGON`/... event trail, and a heartbeat —
all visible on the dashboard within a few seconds. See [Agent](agent.md) for what
`logon`/`lock`/... actually mean (the dev backend's interactive REPL stands in for real Windows
session notifications when not running on Windows).

## Components

| Component         | Path          | Language                    | What it does |
|--------------------|---------------|------------------------------|----------------|
| TimeLord Agent     | `agent/`      | Rust (Windows service)       | Tracks usage sessions and reports them |
| TimeLord Controller| `controller/`| Java 21 / Spring Boot        | Registration, heartbeats, events, dashboard |
| TimeLord Protocol  | `protocol/`  | JSON Schema                  | Not yet populated |

## Further reading

- [Architecture](architecture.md) — component diagram, discovery/registration sequence, event
  delivery and idempotency, security boundary, known limitations
- [Agent](agent.md) — agent internals, CLI, configuration, local dev workflow
- [Controller](controller.md) — controller internals, API, running tests
