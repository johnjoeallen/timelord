# TimeLord

TimeLord is a centrally managed computer-usage policy platform that securely enforces schedules,
session limits, mandatory breaks, connectivity requirements, and remote power controls across
Windows devices.

**TimeLord — The Authority on Computer Time.**

## Components

| Component            | Path          | Language              |
|-----------------------|---------------|------------------------|
| TimeLord Agent        | `agent/`      | Rust (Windows service) |
| TimeLord Controller    | `controller/` | Java 21 / Spring Boot  |
| TimeLord Console       | `web/`        | TypeScript             |
| TimeLord Protocol      | `protocol/`   | JSON Schema            |

## Status

Early scaffolding. The current focus is the agent's local usage-session tracking
(see `agent/README.md`) — recording when a device is actually being used, ahead of
wiring the agent up to a controller. See `docs/` for the full design brief once it lands.

## Layout

```text
timelord/
├── agent/        Windows service agent (Rust)
├── controller/   Central controller service (Java / Spring Boot)
├── web/          Admin console (web UI)
├── protocol/     Versioned wire protocol schemas and examples
├── deployment/   Docker, systemd, and Windows packaging
└── docs/         Architecture, threat model, and operational docs
```
