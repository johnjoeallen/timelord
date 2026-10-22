# TimeLord Protocol

Versioned wire protocol: JSON Schemas for signed messages (policies, leases, commands) under
`schemas/`, with worked examples under `examples/`. Not yet populated — those message types
(policies, leases, commands) don't exist yet. The wire formats that *do* exist today are not
formal schemas yet: the agent/controller API (registration, heartbeats with the resolved schedule,
events, and the schedule and message long polls) is plain JSON over HTTP, documented in
[`docs/architecture.md`](../docs/architecture.md), [`docs/scheduling.md`](../docs/scheduling.md) and
the Rust/Java DTOs (`agent/src/protocol.rs`, `controller/.../agent/*.java`); the local
agent ↔ notifier pipe messages are defined in [`ipc/`](../ipc/src/lib.rs).
