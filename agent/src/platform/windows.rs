//! Windows notification pump.
//!
//! Registers a hidden message-only window for `WM_WTSSESSION_CHANGE`
//! (via `WTSRegisterSessionNotification`, scoped to all sessions so this
//! works from a `LocalSystem` service in Session 0) and for
//! `WM_POWERBROADCAST` suspend/resume notifications, translates them into
//! [`SessionEvent`]s, and feeds them to the [`UsageRecorder`].

use std::cell::RefCell;
use std::sync::OnceLock;

use anyhow::{anyhow, Context};
use tracing::{error, warn};
use windows::core::PCWSTR;
use windows::Win32::Foundation::{HWND, LPARAM, LRESULT, WPARAM};
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::System::Power::RegisterSuspendResumeNotification;
use windows::Win32::System::RemoteDesktop::{
    WTSRegisterSessionNotification, WTSUnRegisterSessionNotification, NOTIFY_FOR_ALL_SESSIONS,
};
use windows::Win32::System::Threading::GetCurrentThreadId;
use windows::Win32::UI::WindowsAndMessaging::{
    CreateWindowExW, DefWindowProcW, DispatchMessageW, GetMessageW, PostQuitMessage,
    PostThreadMessageW, RegisterClassExW, TranslateMessage, CW_USEDEFAULT,
    DEVICE_NOTIFY_WINDOW_HANDLE, HWND_MESSAGE, MSG, PBT_APMRESUMEAUTOMATIC,
    PBT_APMRESUMESUSPEND, PBT_APMSUSPEND, WM_DESTROY, WM_POWERBROADCAST, WM_QUIT,
    WM_WTSSESSION_CHANGE, WNDCLASSEXW, WNDCLASS_STYLES, WS_OVERLAPPED, WTS_CONSOLE_CONNECT,
    WTS_CONSOLE_DISCONNECT, WTS_REMOTE_CONNECT, WTS_REMOTE_DISCONNECT, WTS_SESSION_LOCK,
    WTS_SESSION_LOGOFF, WTS_SESSION_LOGON, WTS_SESSION_UNLOCK,
};

use crate::tracker::SessionEvent;
use crate::UsageRecorder;

const CLASS_NAME: PCWSTR = windows::core::w!("TimeLordAgentNotificationWindow");

thread_local! {
    // The window procedure is a plain `extern "system" fn` with no user-data
    // slot we control before `CreateWindowExW` returns, so the recorder is
    // parked here instead of behind `GWLP_USERDATA`.
    static RECORDER: RefCell<Option<UsageRecorder>> = const { RefCell::new(None) };
}

/// Thread ID of whichever thread is currently running the message pump, so
/// [`request_stop`] can post it a `WM_QUIT` from the service control
/// handler thread.
static PUMP_THREAD_ID: OnceLock<u32> = OnceLock::new();

/// Asks a currently-running [`run`] to stop. Safe to call from any thread;
/// a no-op if the pump isn't running.
pub fn request_stop() {
    if let Some(&tid) = PUMP_THREAD_ID.get() {
        // SAFETY: posting a WM_QUIT to a thread ID we captured from
        // `GetCurrentThreadId` inside `run`; this is the documented way to
        // break a `GetMessageW` loop from another thread.
        unsafe {
            let _ = PostThreadMessageW(tid, WM_QUIT, WPARAM(0), LPARAM(0));
        }
    }
}

pub fn run(recorder: UsageRecorder) -> anyhow::Result<()> {
    RECORDER.with(|cell| *cell.borrow_mut() = Some(recorder));
    // SAFETY: no preconditions; returns the calling thread's ID.
    let _ = PUMP_THREAD_ID.set(unsafe { GetCurrentThreadId() });

    let hwnd = create_message_window().context("creating notification window")?;

    // SAFETY: hwnd is a valid message-only window owned by this thread for
    // the lifetime of the notification registrations below.
    let wts_result = unsafe { WTSRegisterSessionNotification(hwnd, NOTIFY_FOR_ALL_SESSIONS) };
    if wts_result.is_err() {
        return Err(anyhow!("WTSRegisterSessionNotification failed: {:?}", wts_result));
    }

    // SAFETY: hwnd is valid for the duration of this registration.
    let power_notify = unsafe {
        RegisterSuspendResumeNotification(hwnd, DEVICE_NOTIFY_WINDOW_HANDLE)
    }
    .context("RegisterSuspendResumeNotification failed")?;

    let mut msg = MSG::default();
    // SAFETY: standard Win32 message loop; `msg` is valid for the call.
    while unsafe { GetMessageW(&mut msg, None, 0, 0) }.into() {
        unsafe {
            let _ = TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
    }

    unsafe {
        let _ = WTSUnRegisterSessionNotification(hwnd);
        let _ = windows::Win32::System::Power::UnregisterSuspendResumeNotification(power_notify);
    }

    RECORDER.with(|cell| {
        if let Some(mut recorder) = cell.borrow_mut().take() {
            if let Err(err) = recorder.shutdown() {
                error!(?err, "failed to persist final usage session on shutdown");
            }
        }
    });

    Ok(())
}

fn create_message_window() -> anyhow::Result<HWND> {
    // SAFETY: retrieves a handle to this module; no preconditions.
    let module = unsafe { GetModuleHandleW(None) }.context("GetModuleHandleW")?;
    let instance: windows::Win32::Foundation::HINSTANCE = module.into();

    let wc = WNDCLASSEXW {
        cbSize: std::mem::size_of::<WNDCLASSEXW>() as u32,
        style: WNDCLASS_STYLES::default(),
        lpfnWndProc: Some(wndproc),
        hInstance: instance,
        lpszClassName: CLASS_NAME,
        ..Default::default()
    };
    // SAFETY: `wc` is a fully-initialized WNDCLASSEXW.
    let atom = unsafe { RegisterClassExW(&wc) };
    if atom == 0 {
        return Err(anyhow!("RegisterClassExW failed"));
    }

    // SAFETY: all arguments are either valid handles/pointers or the
    // documented sentinel values (e.g. `None`, `CW_USEDEFAULT`).
    let hwnd = unsafe {
        CreateWindowExW(
            Default::default(),
            CLASS_NAME,
            CLASS_NAME,
            WS_OVERLAPPED,
            CW_USEDEFAULT,
            CW_USEDEFAULT,
            CW_USEDEFAULT,
            CW_USEDEFAULT,
            HWND_MESSAGE,
            None,
            Some(&instance),
            None,
        )
    }
    .context("CreateWindowExW failed")?;

    Ok(hwnd)
}

extern "system" fn wndproc(hwnd: HWND, msg: u32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    match msg {
        WM_WTSSESSION_CHANGE => {
            let session_id = lparam.0 as u32;
            if let Some(event) = wts_reason_to_event(wparam.0 as u32, session_id) {
                dispatch(event);
            }
            LRESULT(0)
        }
        WM_POWERBROADCAST => {
            let event = match wparam.0 as u32 {
                PBT_APMSUSPEND => Some(SessionEvent::Suspend),
                PBT_APMRESUMEAUTOMATIC | PBT_APMRESUMESUSPEND => Some(SessionEvent::Resume),
                _ => None,
            };
            if let Some(event) = event {
                dispatch(event);
            }
            LRESULT(1)
        }
        WM_DESTROY => {
            // SAFETY: standard shutdown of this thread's message loop.
            unsafe { PostQuitMessage(0) };
            LRESULT(0)
        }
        // SAFETY: forwards unhandled messages to the default handler, as
        // required for any custom window procedure.
        _ => unsafe { DefWindowProcW(hwnd, msg, wparam, lparam) },
    }
}

fn wts_reason_to_event(reason: u32, session_id: u32) -> Option<SessionEvent> {
    match reason {
        WTS_SESSION_LOGON => Some(SessionEvent::Logon(session_id)),
        WTS_SESSION_LOGOFF => Some(SessionEvent::Logoff(session_id)),
        WTS_SESSION_LOCK => Some(SessionEvent::Lock(session_id)),
        WTS_SESSION_UNLOCK => Some(SessionEvent::Unlock(session_id)),
        WTS_CONSOLE_CONNECT => Some(SessionEvent::ConsoleConnect(session_id)),
        WTS_CONSOLE_DISCONNECT => Some(SessionEvent::ConsoleDisconnect(session_id)),
        WTS_REMOTE_CONNECT => Some(SessionEvent::RemoteConnect(session_id)),
        WTS_REMOTE_DISCONNECT => Some(SessionEvent::RemoteDisconnect(session_id)),
        _ => None,
    }
}

fn dispatch(event: SessionEvent) {
    RECORDER.with(|cell| {
        if let Some(recorder) = cell.borrow_mut().as_mut() {
            if let Err(err) = recorder.handle(event) {
                warn!(?err, ?event, "failed to persist session event");
            }
        }
    });
}
