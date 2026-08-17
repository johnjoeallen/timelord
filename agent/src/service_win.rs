//! Windows Service Control Manager integration.
//!
//! Registers `timelord-agent` as an `OWN_PROCESS` service, reports status
//! transitions to the SCM, and asks the notification pump ([`platform::run`])
//! to stop cleanly on a `SERVICE_CONTROL_STOP` so the current usage session
//! (if any) is closed out before the process exits. `install`/`uninstall`
//! use the SCM API directly (`windows-service`'s `ServiceManager`) rather
//! than shelling out to `sc.exe`.

use std::ffi::OsString;
use std::time::Duration;

use tracing::error;
use windows_service::service::{
    ServiceAccess, ServiceControl, ServiceControlAccept, ServiceErrorControl, ServiceExitCode, ServiceInfo,
    ServiceStartType, ServiceState, ServiceStatus, ServiceType,
};
use windows_service::service_control_handler::{self, ServiceControlHandlerResult};
use windows_service::service_manager::{ServiceManager, ServiceManagerAccess};
use windows_service::{define_windows_service, service_dispatcher};

use crate::config::CliOverrides;
use crate::{agent, data_dir, platform};

pub const SERVICE_NAME: &str = "TimeLordAgent";
const SERVICE_DISPLAY_NAME: &str = "TimeLord Agent";
const SERVICE_DESCRIPTION: &str = "Tracks and reports TimeLord device usage.";
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
            // `Stop` is a manual "net stop"/SCM-initiated stop; `Shutdown`
            // is what the SCM actually sends on a system shutdown/restart —
            // without accepting and handling it too, the process is just
            // killed on shutdown and never gets to close the open usage
            // session or make a final delivery attempt for queued events.
            ServiceControl::Stop | ServiceControl::Shutdown => {
                platform::request_stop();
                ServiceControlHandlerResult::NoError
            }
            ServiceControl::Interrogate => ServiceControlHandlerResult::NoError,
            _ => ServiceControlHandlerResult::NotImplemented,
        }
    };

    let status_handle = service_control_handler::register(SERVICE_NAME, event_handler)?;
    set_status(&status_handle, ServiceState::StartPending, ServiceControlAccept::empty())?;
    set_status(&status_handle, ServiceState::Running, ServiceControlAccept::STOP | ServiceControlAccept::SHUTDOWN)?;

    // Blocks until `platform::request_stop()` is called from the control
    // handler above, closing any open usage session and delivering queued
    // events one last time on the way out.
    let result = agent::run_blocking(&data_dir()?, &CliOverrides::default());

    set_status(&status_handle, ServiceState::Stopped, ServiceControlAccept::empty())?;
    result
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

/// Registers this binary as a `LocalSystem`, auto-start service. Re-running
/// against an already-installed service replaces it (stop, delete,
/// recreate) so this also serves as the upgrade path.
pub fn install() -> anyhow::Result<()> {
    if is_installed()? {
        uninstall()?;
    }

    let manager = ServiceManager::local_computer(None::<&str>, ServiceManagerAccess::CREATE_SERVICE)?;
    let exe_path = std::env::current_exe()?;

    let service_info = ServiceInfo {
        name: OsString::from(SERVICE_NAME),
        display_name: OsString::from(SERVICE_DISPLAY_NAME),
        service_type: SERVICE_TYPE,
        start_type: ServiceStartType::AutoStart,
        error_control: ServiceErrorControl::Normal,
        executable_path: exe_path,
        launch_arguments: vec![],
        dependencies: vec![],
        account_name: None, // LocalSystem
        account_password: None,
    };

    let service = manager.create_service(&service_info, ServiceAccess::CHANGE_CONFIG | ServiceAccess::START)?;
    service.set_description(SERVICE_DESCRIPTION)?;
    service.start(&[] as &[OsString])?;

    println!("{SERVICE_DISPLAY_NAME} installed and started.");
    Ok(())
}

/// Stops (if running) and removes the service.
pub fn uninstall() -> anyhow::Result<()> {
    let manager = ServiceManager::local_computer(None::<&str>, ServiceManagerAccess::CONNECT)?;
    let service = manager.open_service(
        SERVICE_NAME,
        ServiceAccess::STOP | ServiceAccess::DELETE | ServiceAccess::QUERY_STATUS,
    )?;

    let status = service.query_status()?;
    if status.current_state != ServiceState::Stopped {
        service.stop()?;
        // Give the SCM a moment to actually transition before deleting;
        // deleting a still-stopping service just marks it for deletion once
        // it finishes, which is fine either way, but a short wait avoids
        // surprising the caller with an immediate "still running" status.
        std::thread::sleep(Duration::from_millis(500));
    }
    service.delete()?;

    println!("{SERVICE_DISPLAY_NAME} removed.");
    Ok(())
}

fn is_installed() -> anyhow::Result<bool> {
    let manager = ServiceManager::local_computer(None::<&str>, ServiceManagerAccess::CONNECT)?;
    match manager.open_service(SERVICE_NAME, ServiceAccess::QUERY_STATUS) {
        Ok(_) => Ok(true),
        Err(windows_service::Error::Winapi(err)) if err.raw_os_error() == Some(1060) => Ok(false), // ERROR_SERVICE_DOES_NOT_EXIST
        Err(err) => Err(err.into()),
    }
}

/// Human-readable current SCM state, for `timelord-agent status`.
pub fn query_status() -> anyhow::Result<String> {
    let manager = ServiceManager::local_computer(None::<&str>, ServiceManagerAccess::CONNECT)?;
    let service = match manager.open_service(SERVICE_NAME, ServiceAccess::QUERY_STATUS) {
        Ok(service) => service,
        Err(windows_service::Error::Winapi(err)) if err.raw_os_error() == Some(1060) => {
            return Ok("not installed".to_string());
        }
        Err(err) => return Err(err.into()),
    };
    Ok(format!("{:?}", service.query_status()?.current_state))
}
