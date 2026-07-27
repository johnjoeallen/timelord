//! Platform backends that translate OS-level notifications into
//! [`crate::tracker::SessionEvent`]s and feed them to a [`crate::UsageRecorder`].
//!
//! Only the `windows` backend talks to real Win32 APIs; it is compiled only
//! under `cfg(windows)`. The `dev` backend lets the tracker/store be
//! exercised interactively on any OS (used for local development on this
//! Linux workstation, since the target machine is Windows).

#[cfg(windows)]
mod windows;
#[cfg(windows)]
pub use self::windows::{request_stop, run, suspend};

#[cfg(not(windows))]
mod dev;
#[cfg(not(windows))]
pub use dev::{run, suspend};
