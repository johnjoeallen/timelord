//! Windows Service Control Manager integration.
//!
//! Registers `timelord-agent` as an `OWN_PROCESS` service, reports status
//! transitions to the SCM, and asks the notification pump ([`platform::run`])
//! to stop cleanly on a `SERVICE_CONTROL_STOP` so the current usage session
//! (if any) is closed out before the process exits.

use std::ffi::OsString;
use std::time::Duration;

use tracing::error;
use windows_service::service::{
    ServiceControl, ServiceControlAccept, ServiceExitCode, ServiceState, ServiceStatus,
    ServiceType,
};
use windows_service::service_control_handler::{self, ServiceControlHandlerResult};
use windows_service::{define_windows_service, service_dispatcher};

use crate::{data_dir, platform, UsageRecorder};

pub const SERVICE_NAME: &str = "TimeLordAgent";
const SERVICE_TYPE: ServiceType = ServiceType::OWN_PROCESS;

define_windows_service!(ffi_service_main, service_main);

/// Blocks, handing control to the Service Control Manager. Must be called
/// from the process's real `main` — the SCM expects this within a few
/// seconds of process start.
pub fn run() -> anyhow::Result<()> {
    service_dispatcher::start(SERVICE_NAME, ffi_service_main)?;
    Ok(())
}

fn service_main(_arguments: Vec<OsString>) {
    if let Err(err) = run_service() {
        error!(?err, "timelord-agent service exited with an error");
    }
}

fn run_service() -> anyhow::Result<()> {
    let event_handler = move |control_event| -> ServiceControlHandlerResult {
        match control_event {
            ServiceControl::Stop => {
                platform::request_stop();
                ServiceControlHandlerResult::NoError
            }
            ServiceControl::Interrogate => ServiceControlHandlerResult::NoError,
            _ => ServiceControlHandlerResult::NotImplemented,
        }
    };

    let status_handle = service_control_handler::register(SERVICE_NAME, event_handler)?;
    set_status(&status_handle, ServiceState::StartPending, ServiceControlAccept::empty())?;

    let db_path = data_dir()?.join("state.db");
    let recorder = UsageRecorder::open(&db_path)?;

    set_status(&status_handle, ServiceState::Running, ServiceControlAccept::STOP)?;

    // Blocks until `platform::request_stop()` is called from the control
    // handler above (or the message loop otherwise exits), closing any open
    // usage session on the way out.
    platform::run(recorder)?;

    set_status(&status_handle, ServiceState::Stopped, ServiceControlAccept::empty())?;
    Ok(())
}

fn set_status(
    handle: &service_control_handler::ServiceStatusHandle,
    state: ServiceState,
    controls_accepted: ServiceControlAccept,
) -> anyhow::Result<()> {
    handle.set_service_status(ServiceStatus {
        service_type: SERVICE_TYPE,
        current_state: state,
        controls_accepted,
        exit_code: ServiceExitCode::Win32(0),
        checkpoint: 0,
        wait_hint: Duration::default(),
        process_id: None,
    })?;
    Ok(())
}
