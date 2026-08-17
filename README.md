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

TimeLord always runs as two containers — `postgres` + `controller` — wired together by
`compose.yaml`. There's no supported way to `docker run` the controller image by itself; it needs
a Postgres it can reach, and compose is what sets that up.

**From a clone of this repo**, build the controller image locally:

```console
$ cp .env.example .env        # edit TIMELORD_PUBLIC_URL to your LAN IP
$ docker compose up --build   # postgres + controller on :9099, discovery UDP on :45821
```

**Without cloning**, pull the published image instead — grab just the two files compose needs
and start it (omit `--build` so compose pulls `ghcr.io/johnjoeallen/timelord-controller:latest`
rather than trying to build from a `controller/` directory you don't have):

```console
$ curl -O https://raw.githubusercontent.com/johnjoeallen/timelord/main/compose.yaml
$ curl -O https://raw.githubusercontent.com/johnjoeallen/timelord/main/.env.example
$ cp .env.example .env        # edit TIMELORD_PUBLIC_URL to your LAN IP
$ docker compose up -d        # pulls postgres + controller, no source needed
```

Either way, open `http://localhost:9099` for the dashboard, or:

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
