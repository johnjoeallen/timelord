//! Agent configuration: `agent.toml` + environment variable overrides + CLI
//! arguments, resolved with precedence `CLI > env > file > defaults`
//! (design brief section 3).

use std::path::Path;
use std::time::Duration;

use chrono::NaiveTime;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

const DEFAULT_HEARTBEAT_INTERVAL_SECONDS: u64 = 30;
const DEFAULT_EVENT_RETRY_INTERVAL_SECONDS: u64 = 10;
const DEFAULT_DISCOVERY_PORT: u16 = 45821;
const DEFAULT_DISCOVERY_TIMEOUT_SECONDS: u64 = 5;
const DEFAULT_OFFLINE_FALLBACK_ENABLED: bool = true;
const DEFAULT_OFFLINE_FALLBACK_START: &str = "01:00";
const DEFAULT_OFFLINE_FALLBACK_END: &str = "08:30";

/// On-disk shape of `agent.toml`. Every field is optional so a partial or
/// missing file is valid; [`resolve`] fills in the rest.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct AgentFileConfig {
    #[serde(default)]
    pub agent: AgentSection,
    #[serde(default)]
    pub controller: ControllerSection,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct AgentSection {
    #[serde(default)]
    pub device_id: String,
    #[serde(default)]
    pub device_name: String,
    pub heartbeat_interval_seconds: Option<u64>,
    pub event_retry_interval_seconds: Option<u64>,
    pub offline_fallback_enabled: Option<bool>,
    pub offline_fallback_start: Option<String>,
    pub offline_fallback_end: Option<String>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct ControllerSection {
    #[serde(default)]
    pub url: String,
    pub discovery_enabled: Option<bool>,
    pub discovery_port: Option<u16>,
    pub discovery_timeout_seconds: Option<u64>,
}

/// `TIMELORD_*` environment variable overrides, pre-read so [`resolve`]
/// stays a pure function independent of `std::env` (testable without
/// mutating real process environment).
#[derive(Debug, Clone, Default)]
pub struct EnvOverrides {
    pub controller_url: Option<String>,
    pub device_id: Option<String>,
    pub device_name: Option<String>,
    pub discovery_enabled: Option<bool>,
}

impl EnvOverrides {
    pub fn from_process_env() -> Self {
        Self {
            controller_url: non_empty(std::env::var("TIMELORD_CONTROLLER_URL").ok()),
            device_id: non_empty(std::env::var("TIMELORD_DEVICE_ID").ok()),
            device_name: non_empty(std::env::var("TIMELORD_DEVICE_NAME").ok()),
            discovery_enabled: std::env::var("TIMELORD_DISCOVERY_ENABLED").ok().and_then(|v| parse_bool(&v)),
        }
    }
}

/// CLI-argument overrides, highest precedence.
#[derive(Debug, Clone, Default)]
pub struct CliOverrides {
    pub controller_url: Option<String>,
    pub device_id: Option<String>,
    pub device_name: Option<String>,
    pub discovery_enabled: Option<bool>,
}

/// Fully-resolved configuration the rest of the agent operates on.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AgentConfig {
    pub device_id: Uuid,
    /// Whether `device_id` was freshly generated this call (the caller
    /// should persist it back to `agent.toml`).
    pub device_id_generated: bool,
    pub device_name: String,
    pub heartbeat_interval: Duration,
    pub event_retry_interval: Duration,
    pub controller_url: Option<String>,
    pub discovery_enabled: bool,
    pub discovery_port: u16,
    pub discovery_timeout: Duration,
    /// When the agent can't reach the controller, it falls back to putting
    /// the device to sleep during this local-time window (e.g. an overnight
    /// "off" period), so usage policy still applies without connectivity.
    pub offline_fallback_enabled: bool,
    pub offline_fallback_start: NaiveTime,
    pub offline_fallback_end: NaiveTime,
}

/// Resolves a fully-populated [`AgentConfig`] from the file contents, env
/// overrides, CLI overrides, and a hostname supplier (injected so this stays
/// pure/testable rather than calling the real OS hostname API).
pub fn resolve(
    file: &AgentFileConfig,
    env: &EnvOverrides,
    cli: &CliOverrides,
    default_hostname: impl FnOnce() -> String,
) -> AgentConfig {
    let device_id_str = cli.device_id.clone().or_else(|| env.device_id.clone()).or_else(|| non_empty(Some(file.agent.device_id.clone())));
    let (device_id, device_id_generated) = match device_id_str.and_then(|s| Uuid::parse_str(&s).ok()) {
        Some(id) => (id, false),
        None => (Uuid::new_v4(), true),
    };

    let device_name = cli
        .device_name
        .clone()
        .or_else(|| env.device_name.clone())
        .or_else(|| non_empty(Some(file.agent.device_name.clone())))
        .unwrap_or_else(default_hostname);

    let controller_url = cli
        .controller_url
        .clone()
        .or_else(|| env.controller_url.clone())
        .or_else(|| non_empty(Some(file.controller.url.clone())));

    let discovery_enabled = cli
        .discovery_enabled
        .or(env.discovery_enabled)
        .or(file.controller.discovery_enabled)
        .unwrap_or(true);

    AgentConfig {
        device_id,
        device_id_generated,
        device_name,
        heartbeat_interval: Duration::from_secs(
            file.agent.heartbeat_interval_seconds.unwrap_or(DEFAULT_HEARTBEAT_INTERVAL_SECONDS),
        ),
        event_retry_interval: Duration::from_secs(
            file.agent.event_retry_interval_seconds.unwrap_or(DEFAULT_EVENT_RETRY_INTERVAL_SECONDS),
        ),
        controller_url,
        discovery_enabled,
        discovery_port: file.controller.discovery_port.unwrap_or(DEFAULT_DISCOVERY_PORT),
        discovery_timeout: Duration::from_secs(
            file.controller.discovery_timeout_seconds.unwrap_or(DEFAULT_DISCOVERY_TIMEOUT_SECONDS),
        ),
        offline_fallback_enabled: file.agent.offline_fallback_enabled.unwrap_or(DEFAULT_OFFLINE_FALLBACK_ENABLED),
        offline_fallback_start: parse_time_or_default(file.agent.offline_fallback_start.as_deref(), DEFAULT_OFFLINE_FALLBACK_START),
        offline_fallback_end: parse_time_or_default(file.agent.offline_fallback_end.as_deref(), DEFAULT_OFFLINE_FALLBACK_END),
    }
}

/// Parses an `agent.toml` `"HH:MM"` time string, falling back to `default`
/// (itself always a valid `"HH:MM"` literal) if `value` is missing, blank,
/// or unparseable.
fn parse_time_or_default(value: Option<&str>, default: &str) -> NaiveTime {
    let raw = value.map(str::trim).filter(|s| !s.is_empty()).unwrap_or(default);
    NaiveTime::parse_from_str(raw, "%H:%M").unwrap_or_else(|err| {
        tracing::warn!(value = raw, ?err, "invalid offline fallback time in agent.toml, using default");
        NaiveTime::parse_from_str(default, "%H:%M").expect("default offline fallback time is valid")
    })
}

fn non_empty(value: Option<String>) -> Option<String> {
    value.filter(|s| !s.trim().is_empty())
}

fn parse_bool(value: &str) -> Option<bool> {
    match value.trim().to_ascii_lowercase().as_str() {
        "1" | "true" | "yes" | "on" => Some(true),
        "0" | "false" | "no" | "off" => Some(false),
        _ => None,
    }
}

/// Loads `agent.toml` from `path`, treating a missing file as an empty
/// (all-default) config rather than an error — the file is created on
/// first successful save.
pub fn load_file(path: &Path) -> anyhow::Result<AgentFileConfig> {
    match std::fs::read_to_string(path) {
        Ok(contents) => Ok(toml::from_str(&contents)?),
        Err(err) if err.kind() == std::io::ErrorKind::NotFound => Ok(AgentFileConfig::default()),
        Err(err) => Err(err.into()),
    }
}

pub fn save_file(path: &Path, config: &AgentFileConfig) -> anyhow::Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(path, toml::to_string_pretty(config)?)?;
    Ok(())
}

/// Persists a freshly-generated device ID into `agent.toml` at `path`,
/// preserving every other field already there.
pub fn persist_device_id(path: &Path, device_id: Uuid) -> anyhow::Result<()> {
    let mut file = load_file(path)?;
    file.agent.device_id = device_id.to_string();
    save_file(path, &file)
}

/// Persists a discovered (or explicitly forced) controller URL into
/// `agent.toml`, per design brief section 3: "Once a controller is
/// successfully discovered, persist its URL" and "Do not overwrite a
/// manually configured URL unless explicitly requested" — callers only
/// invoke this when discovery actually ran, which per [`resolve`]'s
/// precedence only happens when no URL was already configured, or the
/// caller explicitly forced rediscovery (`discover --force`).
pub fn persist_controller_url(path: &Path, url: &str) -> anyhow::Result<()> {
    let mut file = load_file(path)?;
    file.controller.url = url.to_string();
    save_file(path, &file)
}

/// Loads `agent.toml`, layers in environment overrides, resolves the final
/// config, and persists a freshly-generated device ID immediately (so a
/// second call in the same process — or the next run — sees the same ID).
/// The single entry point `main.rs` and [`crate::agent::run_blocking`] both
/// use.
pub fn load(path: &Path, cli: &CliOverrides) -> anyhow::Result<AgentConfig> {
    let file = load_file(path)?;
    let env = EnvOverrides::from_process_env();
    let hostname = || hostname::get().ok().and_then(|h| h.into_string().ok()).unwrap_or_else(|| "unknown-host".to_string());
    let resolved = resolve(&file, &env, cli, hostname);

    if resolved.device_id_generated {
        persist_device_id(path, resolved.device_id)?;
        tracing::info!(device_id = %resolved.device_id, "generated and persisted new device ID");
    }

    Ok(resolved)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn empty_file() -> AgentFileConfig {
        AgentFileConfig::default()
    }

    #[test]
    fn missing_device_id_is_generated() {
        let cfg = resolve(&empty_file(), &EnvOverrides::default(), &CliOverrides::default(), || "host".into());
        assert!(cfg.device_id_generated);
    }

    #[test]
    fn configured_device_id_is_parsed_and_not_regenerated() {
        let mut file = empty_file();
        file.agent.device_id = "764f814d-f71c-41d4-b89f-d4f914653036".into();
        let cfg = resolve(&file, &EnvOverrides::default(), &CliOverrides::default(), || "host".into());
        assert!(!cfg.device_id_generated);
        assert_eq!(cfg.device_id.to_string(), "764f814d-f71c-41d4-b89f-d4f914653036");
    }

    #[test]
    fn missing_device_name_defaults_to_hostname() {
        let cfg = resolve(&empty_file(), &EnvOverrides::default(), &CliOverrides::default(), || "gaming-pc".into());
        assert_eq!(cfg.device_name, "gaming-pc");
    }

    #[test]
    fn precedence_is_cli_then_env_then_file_then_default() {
        let mut file = empty_file();
        file.controller.url = "http://from-file:8080".into();

        // File alone.
        let cfg = resolve(&file, &EnvOverrides::default(), &CliOverrides::default(), || "h".into());
        assert_eq!(cfg.controller_url.as_deref(), Some("http://from-file:8080"));

        // Env beats file.
        let env = EnvOverrides { controller_url: Some("http://from-env:8080".into()), ..Default::default() };
        let cfg = resolve(&file, &env, &CliOverrides::default(), || "h".into());
        assert_eq!(cfg.controller_url.as_deref(), Some("http://from-env:8080"));

        // CLI beats env and file.
        let cli = CliOverrides { controller_url: Some("http://from-cli:8080".into()), ..Default::default() };
        let cfg = resolve(&file, &env, &cli, || "h".into());
        assert_eq!(cfg.controller_url.as_deref(), Some("http://from-cli:8080"));
    }

    #[test]
    fn no_controller_url_configured_anywhere_is_none() {
        let cfg = resolve(&empty_file(), &EnvOverrides::default(), &CliOverrides::default(), || "h".into());
        assert_eq!(cfg.controller_url, None);
    }

    #[test]
    fn discovery_enabled_defaults_to_true() {
        let cfg = resolve(&empty_file(), &EnvOverrides::default(), &CliOverrides::default(), || "h".into());
        assert!(cfg.discovery_enabled);
    }

    #[test]
    fn env_can_disable_discovery() {
        let env = EnvOverrides { discovery_enabled: Some(false), ..Default::default() };
        let cfg = resolve(&empty_file(), &env, &CliOverrides::default(), || "h".into());
        assert!(!cfg.discovery_enabled);
    }

    #[test]
    fn blank_file_values_are_treated_as_unset() {
        let mut file = empty_file();
        file.controller.url = "   ".into();
        let cfg = resolve(&file, &EnvOverrides::default(), &CliOverrides::default(), || "h".into());
        assert_eq!(cfg.controller_url, None);
    }

    #[test]
    fn defaults_match_documented_values() {
        let cfg = resolve(&empty_file(), &EnvOverrides::default(), &CliOverrides::default(), || "h".into());
        assert_eq!(cfg.heartbeat_interval, Duration::from_secs(30));
        assert_eq!(cfg.event_retry_interval, Duration::from_secs(10));
        assert_eq!(cfg.discovery_port, 45821);
        assert_eq!(cfg.discovery_timeout, Duration::from_secs(5));
        assert!(cfg.offline_fallback_enabled);
        assert_eq!(cfg.offline_fallback_start, NaiveTime::from_hms_opt(1, 0, 0).unwrap());
        assert_eq!(cfg.offline_fallback_end, NaiveTime::from_hms_opt(8, 30, 0).unwrap());
    }

    #[test]
    fn offline_fallback_can_be_disabled_and_retimed_from_file() {
        let mut file = empty_file();
        file.agent.offline_fallback_enabled = Some(false);
        file.agent.offline_fallback_start = Some("22:00".into());
        file.agent.offline_fallback_end = Some("06:00".into());
        let cfg = resolve(&file, &EnvOverrides::default(), &CliOverrides::default(), || "h".into());
        assert!(!cfg.offline_fallback_enabled);
        assert_eq!(cfg.offline_fallback_start, NaiveTime::from_hms_opt(22, 0, 0).unwrap());
        assert_eq!(cfg.offline_fallback_end, NaiveTime::from_hms_opt(6, 0, 0).unwrap());
    }

    #[test]
    fn invalid_offline_fallback_time_falls_back_to_default() {
        let mut file = empty_file();
        file.agent.offline_fallback_start = Some("not-a-time".into());
        let cfg = resolve(&file, &EnvOverrides::default(), &CliOverrides::default(), || "h".into());
        assert_eq!(cfg.offline_fallback_start, NaiveTime::from_hms_opt(1, 0, 0).unwrap());
    }

    #[test]
    fn load_missing_file_returns_default_not_error() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("does-not-exist.toml");
        let file = load_file(&path).unwrap();
        assert_eq!(file.agent.device_id, "");
    }

    #[test]
    fn save_then_load_round_trips() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("agent.toml");
        let mut file = AgentFileConfig::default();
        file.agent.device_name = "Gaming PC".into();
        file.controller.url = "http://192.168.1.20:8080".into();
        save_file(&path, &file).unwrap();

        let loaded = load_file(&path).unwrap();
        assert_eq!(loaded.agent.device_name, "Gaming PC");
        assert_eq!(loaded.controller.url, "http://192.168.1.20:8080");
    }

    #[test]
    fn persist_device_id_preserves_other_fields() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("agent.toml");
        let mut file = AgentFileConfig::default();
        file.agent.device_name = "Gaming PC".into();
        save_file(&path, &file).unwrap();

        let id = Uuid::new_v4();
        persist_device_id(&path, id).unwrap();

        let loaded = load_file(&path).unwrap();
        assert_eq!(loaded.agent.device_id, id.to_string());
        assert_eq!(loaded.agent.device_name, "Gaming PC");
    }

    #[test]
    fn persist_controller_url_preserves_other_fields() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("agent.toml");
        let mut file = AgentFileConfig::default();
        file.agent.device_name = "Gaming PC".into();
        save_file(&path, &file).unwrap();

        persist_controller_url(&path, "http://192.168.1.20:8080").unwrap();

        let loaded = load_file(&path).unwrap();
        assert_eq!(loaded.controller.url, "http://192.168.1.20:8080");
        assert_eq!(loaded.agent.device_name, "Gaming PC");
    }
}
