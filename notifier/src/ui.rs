//! What the user actually sees. On Windows these are WinRT toasts; elsewhere
//! (development) they are printed so the loop can still be exercised.

use timelord_ipc::Message;
use uuid::Uuid;

use crate::text::ToastText;

/// Toast for an upcoming action. Keyed by the action id, so each tighter warning *replaces* the
/// looser one in place instead of stacking, and a cancelled action can remove it.
pub fn show_warning(action_id: Uuid, text: &ToastText) {
    imp::show(&action_id.to_string(), "timelord-actions", text);
}

pub fn dismiss_warning(action_id: Uuid) {
    imp::dismiss(&action_id.to_string(), "timelord-actions");
}

pub fn show_message(message: &Message, text: &ToastText) {
    imp::show(&message.id.to_string(), "timelord-messages", text);
}

/// One-time setup; `false` means another notifier is already running for this user session.
pub fn init() -> bool {
    imp::init()
}

#[cfg(not(windows))]
mod imp {
    use crate::text::ToastText;

    pub fn init() -> bool {
        true
    }

    pub fn show(tag: &str, _group: &str, text: &ToastText) {
        println!("[toast {tag}{}] {} - {}", if text.urgent { " URGENT" } else { "" }, text.title, text.body);
    }

    pub fn dismiss(tag: &str, _group: &str) {
        println!("[dismiss {tag}]");
    }
}

#[cfg(windows)]
mod imp {
    use windows::core::{w, HSTRING};
    use windows::Data::Xml::Dom::XmlDocument;
    use windows::Win32::Foundation::{GetLastError, ERROR_ALREADY_EXISTS};
    use windows::Win32::System::Com::{CoInitializeEx, COINIT_MULTITHREADED};
    use windows::Win32::System::Registry::{
        RegCloseKey, RegCreateKeyExW, RegSetValueExW, HKEY, HKEY_CURRENT_USER, KEY_SET_VALUE, REG_OPTION_NON_VOLATILE,
        REG_SZ,
    };
    use windows::Win32::System::Threading::CreateMutexW;
    use windows::UI::Notifications::{ToastNotification, ToastNotificationManager};

    use crate::text::ToastText;

    /// Identity our toasts are attributed to. Unpackaged apps must register one or Windows
    /// may drop their toasts or label them with the wrong name.
    const AUMID: &str = "TimeLord.Notifier";

    pub fn init() -> bool {
        // SAFETY: standard COM/WinRT initialisation for this thread; failure (already initialised
        // with a different model) is harmless for our use, so the result is ignored.
        unsafe {
            let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
        }

        // SAFETY: the name is a valid NUL-terminated string. `Local\\` scopes the mutex to this
        // logon session, so each user gets their own notifier. The handle is intentionally leaked:
        // it must live as long as the process to keep the mutex held.
        let first = unsafe {
            match CreateMutexW(None, true, w!("Local\\TimeLordNotifier")) {
                Ok(_handle) => GetLastError() != ERROR_ALREADY_EXISTS,
                Err(_) => true,
            }
        };
        if first {
            if let Err(err) = register_aumid() {
                crate::logfile::log(&format!("could not register toast identity: {err}"));
            }
        }
        first
    }

    /// Per-user registration (no admin needed): HKCU\Software\Classes\AppUserModelId\<AUMID>.
    fn register_aumid() -> windows::core::Result<()> {
        let mut key = HKEY::default();
        // SAFETY: valid NUL-terminated key name and out-pointer; the key is closed below.
        unsafe {
            RegCreateKeyExW(
                HKEY_CURRENT_USER,
                w!("Software\\Classes\\AppUserModelId\\TimeLord.Notifier"),
                0,
                None,
                REG_OPTION_NON_VOLATILE,
                KEY_SET_VALUE,
                None,
                &mut key,
                None,
            )
            .ok()?;
            let name: Vec<u8> = "TimeLord\0".encode_utf16().flat_map(u16::to_le_bytes).collect();
            let result = RegSetValueExW(key, w!("DisplayName"), 0, REG_SZ, Some(&name)).ok();
            let _ = RegCloseKey(key);
            result
        }
    }

    fn escape(text: &str) -> String {
        text.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;").replace('\'', "&apos;")
    }

    fn toast_xml(text: &ToastText) -> String {
        // "reminder" keeps the toast on screen until dismissed, but Windows only honours it when
        // the toast has at least one button, hence the system Dismiss action.
        let (scenario, actions) = if text.urgent {
            (
                " scenario=\"reminder\"",
                "<actions><action content=\"Dismiss\" arguments=\"dismiss\" activationType=\"system\"/></actions>",
            )
        } else {
            ("", "")
        };
        format!(
            "<toast{scenario}><visual><binding template=\"ToastGeneric\"><text>{}</text><text>{}</text></binding></visual>{actions}</toast>",
            escape(&text.title),
            escape(&text.body)
        )
    }

    fn try_show(tag: &str, group: &str, text: &ToastText) -> windows::core::Result<()> {
        let doc = XmlDocument::new()?;
        doc.LoadXml(&HSTRING::from(toast_xml(text)))?;
        let toast = ToastNotification::CreateToastNotification(&doc)?;
        toast.SetTag(&HSTRING::from(tag))?;
        toast.SetGroup(&HSTRING::from(group))?;
        ToastNotificationManager::CreateToastNotifierWithId(&HSTRING::from(AUMID))?.Show(&toast)
    }

    pub fn show(tag: &str, group: &str, text: &ToastText) {
        if let Err(err) = try_show(tag, group, text) {
            crate::logfile::log(&format!("could not show toast: {err}"));
        }
    }

    pub fn dismiss(tag: &str, group: &str) {
        let result = ToastNotificationManager::History()
            .and_then(|h| h.RemoveGroupedTagWithId(&HSTRING::from(tag), &HSTRING::from(group), &HSTRING::from(AUMID)));
        if let Err(err) = result {
            crate::logfile::log(&format!("could not dismiss toast: {err}"));
        }
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn xml_is_escaped_and_urgent_toasts_carry_a_button() {
            let urgent = ToastText { title: "A & <B>".into(), body: "it's \"x\"".into(), urgent: true };
            let xml = toast_xml(&urgent);
            assert!(xml.contains("A &amp; &lt;B&gt;") && xml.contains("it&apos;s &quot;x&quot;"), "{xml}");
            assert!(xml.contains("scenario=\"reminder\"") && xml.contains("<actions>"));

            let calm = toast_xml(&ToastText { urgent: false, ..urgent });
            assert!(!calm.contains("scenario") && !calm.contains("<actions>"));
        }
    }
}
