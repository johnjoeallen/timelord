# TimeLord

TimeLord is a centrally managed computer-usage policy platform that securely enforces schedules,
session limits, mandatory breaks, connectivity requirements, and remote power controls across
Windows devices.

**TimeLord — The Authority on Computer Time.**

> **⚠ Phase 1 security warning:** this build uses plain, unauthenticated HTTP — no TLS, no
> request signing, no access control. It is meant for a trusted local/dev network only. See
> [`docs/architecture.md`](docs/architecture.md#security-boundary).

## Components

| Component            | Path          | Language                | Status                          |
|-----------------------|---------------|--------------------------|----------------------------------|
| TimeLord Agent        | `agent/`      | Rust (Windows service)   | Phase 1: usage tracking + reporting |
| TimeLord Controller    | `controller/` | Java 21 / Spring Boot    | Phase 1: registration, events, UI |
| TimeLord Console       | (controller)  | Thymeleaf (server-rendered) | Phase 1: dashboard/devices/events |
| TimeLord Protocol      | `protocol/`   | JSON Schema              | Not yet populated |

## Quick start

```console
$ cp .env.example .env        # edit TIMELORD_PUBLIC_URL to your LAN IP
$ docker compose up --build   # postgres + controller on :8080, discovery UDP on :45821
```

Open `http://localhost:8080` for the dashboard, or:

```console
$ curl http://localhost:8080/api/v1/system/info
```

### Try the agent against it (from a dev machine, any OS)

```console
$ cd agent
$ TIMELORD_CONTROLLER_URL=http://localhost:8080 cargo run -- run
timelord-agent> logon
timelord-agent> lock
timelord-agent> quit
```

This registers a device, sends an `AGENT_STARTED`/`USER_LOGON`/... event trail, and a heartbeat —
all visible on the dashboard within a few seconds. See `agent/README.md` for what `logon`/`lock`/...
actually mean (the dev backend's interactive REPL stands in for real Windows session
notifications when not running on Windows).

## Layout

```text
timelord/
├── agent/        Windows service agent (Rust)
├── controller/   Central controller service (Java / Spring Boot)
├── protocol/     Versioned wire protocol schemas and examples (not yet populated)
├── deployment/   Docker, systemd, and Windows packaging
├── docs/         Architecture, threat model, and operational docs
└── compose.yaml  postgres + controller, for local/dev deployment
```

## Documentation

- [`docs/architecture.md`](docs/architecture.md) — component diagram, discovery sequence,
  event delivery/idempotency, security boundary, known limitations
- [`agent/README.md`](agent/README.md) — agent internals, CLI, local dev workflow
- [`controller/README.md`](controller/README.md) — controller internals, API, running tests
- [`deployment/windows/README.md`](deployment/windows/README.md) — Windows service install
