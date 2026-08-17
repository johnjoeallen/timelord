# TimeLord Protocol

Versioned wire protocol: JSON Schemas for signed messages (policies, leases, commands) under
`schemas/`, with worked examples under `examples/`. Not yet populated — those message types
(policies, leases, commands) don't exist yet since Phase 1 is monitoring only. The agent/controller
wire format that *does* exist today (registration, heartbeats, events) is plain JSON over HTTP,
documented in [`docs/architecture.md`](../docs/architecture.md) and the Rust/Java DTOs
(`agent/src/protocol.rs`, `controller/.../agent/*.java`) rather than a formal schema yet.
