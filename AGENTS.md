# Agent Instructions

## Project Rules

- The version lives in `agent/Cargo.toml`, `notifier/Cargo.toml` and `ipc/Cargo.toml`, which must all
  carry the same value (update each `Cargo.lock` too). The agent reports it, and a release tag
  `timelord-vX.Y.Z` must match it or the release workflow fails.
- When asked to commit bug fixes, bump the patch version in all three as part of the commit.
- When asked to commit features, bump the minor version in all three as part of the commit.
- Major version changes are managed manually.
- Tag a release only after its version bump is committed and pushed.
