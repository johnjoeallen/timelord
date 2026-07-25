//! Ties config, discovery, the HTTP client, and the local event queue
//! together into the running agent's networking loop (design brief
//! section 17). The synchronous usage-tracking side ([`crate::UsageRecorder`])
//! runs independently on the platform message pump thread; this is the
//! async half that talks to the controller.

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

use chrono::Utc;
use tokio::sync::Mutex;
use tracing::{error, info, warn};
use uuid::Uuid;

use crate::config::{self, AgentConfig, CliOverrides};
use crate::controller_client::ControllerClient;
use crate::discovery;
use crate::events;
use crate::protocol::{EventSubmissionRequest, HeartbeatRequest, RegisterRequest};
use crate::queue::EventQueue;
use crate::{platform, UsageRecorder};

pub const AGENT_VERSION: &str = env!("CARGO_PKG_VERSION");
const EVENT_BATCH_SIZE: usize = 100;
const RECONNECT_BACKOFF: Duration = Duration::from_secs(15);

/// Shared handle the CLI's other commands (`status`, `test-event`) also use.
pub struct Agent {
    pub config: AgentConfig,
    pub agent_toml_path: PathBuf,
    pub queue: Arc<Mutex<EventQueue>>,
    connected: AtomicBool,
}

impl Agent {
    pub fn new(config: AgentConfig, agent_toml_path: PathBuf, queue: Arc<Mutex<EventQueue>>) -> Self {
        Self { config, agent_toml_path, queue, connected: AtomicBool::new(false) }
    }

    pub fn is_connected(&self) -> bool {
        self.connected.load(Ordering::Relaxed)
    }

    pub async fn enqueue(&self, event: crate::queue::QueuedEvent) {
        if let Err(err) = self.queue.lock().await.enqueue(&event, Utc::now()) {
            error!(?err, "failed to persist event locally");
        }
    }

    /// Resolves a controller URL: explicit config wins outright; otherwise
    /// runs active UDP discovery and persists whatever it finds. Never
    /// returns `Ok(None)` — retries discovery with a fixed backoff instead,
    /// since without a controller URL there is nothing else useful to do.
    pub async fn resolve_controller_url(&self, force_rediscover: bool) -> String {
        if !force_rediscover {
            if let Some(url) = &self.config.controller_url {
                return url.clone();
            }
        }

        loop {
            self.enqueue(events::controller_discovery_started()).await;
            info!(port = self.config.discovery_port, "starting controller discovery");
            let candidates = discovery::discover(
                self.config.device_id,
                AGENT_VERSION,
                &self.config.device_name,
                self.config.discovery_port,
                self.config.discovery_timeout,
            )
            .await;

            match candidates {
                Ok(found) if !found.is_empty() => {
                    let best = discovery::select_best(&found).expect("non-empty").clone();
                    info!(controller_id = %best.controller_id, url = %best.controller_url, "discovered controller");
                    self.enqueue(events::controller_discovered(best.controller_id, &best.controller_name, &best.controller_url))
                        .await;
                    if let Err(err) = config::persist_controller_url(&self.agent_toml_path, &best.controller_url) {
                        warn!(?err, "failed to persist discovered controller URL");
                    }
                    return best.controller_url;
                }
                Ok(_) => {
                    warn!("no controller responded to discovery");
                    self.enqueue(events::controller_discovery_failed("no controller responded")).await;
                }
                Err(err) => {
                    warn!(?err, "controller discovery failed");
                    self.enqueue(events::controller_discovery_failed(&err.to_string())).await;
                }
            }
            tokio::time::sleep(RECONNECT_BACKOFF).await;
        }
    }

    fn register_request(&self) -> RegisterRequest {
        let (operating_system, operating_system_version) = os_info();
        RegisterRequest {
            device_id: self.config.device_id,
            device_name: self.config.device_name.clone(),
            hostname: self.config.device_name.clone(),
            agent_version: AGENT_VERSION.to_string(),
            operating_system,
            operating_system_version,
            architecture: std::env::consts::ARCH.to_string(),
            local_ip_addresses: local_ip_addresses(),
        }
    }

    /// Registers (or re-registers) with `client`, retrying with a fixed
    /// backoff on failure — there's nothing better to do while unregistered.
    pub async fn register(&self, client: &ControllerClient) -> Uuid {
        loop {
            match client.register(&self.register_request()).await {
                Ok(response) => {
                    info!(device_id = %response.device_id, controller_id = %response.controller_id, "registered");
                    self.enqueue(events::registration_succeeded(response.device_id)).await;
                    self.connected.store(true, Ordering::Relaxed);
                    return response.device_id;
                }
                Err(err) => {
                    warn!(?err, "registration failed, retrying");
                    self.enqueue(events::registration_failed(&err.to_string())).await;
                    tokio::time::sleep(RECONNECT_BACKOFF).await;
                }
            }
        }
    }

    /// Enqueues a `CONTROLLER_DISCONNECTED` event only on the falling edge
    /// (connected -> disconnected), not on every failed call.
    async fn note_disconnected(&self, reason: &str) {
        if self.connected.swap(false, Ordering::Relaxed) {
            self.enqueue(events::controller_disconnected(reason)).await;
        }
    }

    /// Enqueues a `CONTROLLER_CONNECTED` event only on the rising edge.
    async fn note_connected(&self, controller_url: &str) {
        if !self.connected.swap(true, Ordering::Relaxed) {
            self.enqueue(events::controller_connected(controller_url)).await;
        }
    }
}

/// Periodically sends heartbeats. Re-registers on `404 DEVICE_NOT_FOUND`
/// (design brief section 17) and keeps retrying indefinitely otherwise.
pub async fn heartbeat_loop(agent: Arc<Agent>, client: Arc<Mutex<(ControllerClient, Uuid)>>) {
    let mut interval = tokio::time::interval(agent.config.heartbeat_interval);
    loop {
        interval.tick().await;
        let (client_ref, device_id) = {
            let guard = client.lock().await;
            (guard.0.base_url().to_string(), guard.1)
        };
        let request = HeartbeatRequest {
            event_id: Uuid::new_v4(),
            occurred_at: Utc::now(),
            agent_version: AGENT_VERSION.to_string(),
            uptime_seconds: 0,
            service_state: "RUNNING".to_string(),
            current_user: None,
            // TODO: wire in the live value from UsageTracker once it's
            // shared across the platform-pump thread and this task.
            session_state: "UNKNOWN".to_string(),
            idle_seconds: None,
            controller_url: Some(client_ref.clone()),
        };
        let result = {
            let guard = client.lock().await;
            guard.0.heartbeat(device_id, &request).await
        };
        match result {
            Ok(_) => {
                agent.note_connected(&client_ref).await;
                agent.enqueue(events::heartbeat_sent()).await;
            }
            Err(err) if err.is_device_not_found() => {
                warn!("controller no longer recognises this device; re-registering");
                agent.note_disconnected("device not found; re-registering").await;
                let mut guard = client.lock().await;
                let new_id = agent.register(&guard.0).await;
                guard.1 = new_id;
            }
            Err(err) => {
                warn!(?err, "heartbeat failed");
                agent.note_disconnected(&err.to_string()).await;
            }
        }
    }
}

/// Drains due events from the local queue and submits them in batches,
/// treating both `ACCEPTED` and `DUPLICATE` results as delivered (design
/// brief section 9: "Treat duplicate acknowledgement as successful
/// delivery"). Runs on a short fixed interval since the queue itself
/// encodes real backoff via `next_attempt_at`.
pub async fn delivery_loop(agent: Arc<Agent>, client: Arc<Mutex<(ControllerClient, Uuid)>>) {
    let mut interval = tokio::time::interval(agent.config.event_retry_interval);
    loop {
        interval.tick().await;
        let pending = {
            let queue = agent.queue.lock().await;
            match queue.due_batch(Utc::now(), EVENT_BATCH_SIZE) {
                Ok(rows) => rows,
                Err(err) => {
                    error!(?err, "failed to read event queue");
                    continue;
                }
            }
        };
        if pending.is_empty() {
            continue;
        }

        let request = EventSubmissionRequest { events: pending.iter().map(|p| p.event.to_event_item()).collect() };
        let (submit_result, base_url) = {
            let guard = client.lock().await;
            (guard.0.submit_events(guard.1, &request).await, guard.0.base_url().to_string())
        };

        match submit_result {
            Ok(response) => {
                agent.note_connected(&base_url).await;
                let queue = agent.queue.lock().await;
                for (delivery, result) in pending.iter().zip(response.results.iter()) {
                    use crate::protocol::EventResultStatus;
                    match result.status {
                        EventResultStatus::Accepted | EventResultStatus::Duplicate => {
                            if let Err(err) = queue.mark_delivered(delivery.row_id) {
                                error!(?err, "failed to mark event delivered");
                            }
                        }
                        EventResultStatus::Rejected => {
                            warn!(event_id = %delivery.event.event_id, reason = ?result.reason, "controller rejected event");
                            if let Err(err) = queue.mark_failed(delivery.row_id, delivery.attempt_count, Utc::now()) {
                                error!(?err, "failed to mark event failed");
                            }
                        }
                    }
                }
            }
            Err(err) => {
                warn!(?err, batch_size = pending.len(), "event submission failed; will retry");
                agent.note_disconnected(&err.to_string()).await;
                let queue = agent.queue.lock().await;
                for delivery in &pending {
                    if let Err(err) = queue.mark_failed(delivery.row_id, delivery.attempt_count, Utc::now()) {
                        error!(?err, "failed to mark event failed");
                    }
                }
            }
        }
    }
}

#[cfg(windows)]
fn os_info() -> (String, String) {
    // Real product-name/build-number detection (registry read) is a
    // follow-up; this is enough to identify the platform in the UI today.
    ("Windows".to_string(), String::new())
}

#[cfg(not(windows))]
fn os_info() -> (String, String) {
    (std::env::consts::OS.to_string(), String::new())
}

fn local_ip_addresses() -> Vec<String> {
    match local_ip_address::list_afinet_netifas() {
        Ok(interfaces) => interfaces
            .into_iter()
            .map(|(_, ip)| ip)
            .filter(|ip| !ip.is_loopback())
            .map(|ip| ip.to_string())
            .collect(),
        Err(err) => {
            warn!(?err, "failed to enumerate local IP addresses");
            Vec::new()
        }
    }
}

/// The whole agent lifetime in one call: load config, open the usage store
/// and event queue, start the networking loop in the background, and run
/// the platform notification pump on the calling thread until shutdown.
/// Shared by `timelord-agent run` (main.rs) and the SCM service entry point
/// (service_win.rs) — the only difference between them is who calls this
/// and how they report status around it.
pub fn run_blocking(dir: &std::path::Path, cli: &CliOverrides) -> anyhow::Result<()> {
    let agent_toml_path = dir.join("agent.toml");
    let config = config::load(&agent_toml_path, cli)?;

    let queue = Arc::new(Mutex::new(EventQueue::open(&dir.join("events.db"))?));
    let recorder = UsageRecorder::open(&dir.join("state.db"))?.with_event_queue(queue.clone());
    let agent = Arc::new(Agent::new(config, agent_toml_path, queue.clone()));

    let rt = tokio::runtime::Runtime::new()?;
    rt.block_on(agent.enqueue(events::agent_started(AGENT_VERSION, "STARTUP")));

    let networking = {
        let agent = agent.clone();
        rt.spawn(async move {
            let url = agent.resolve_controller_url(false).await;
            let client = ControllerClient::new(&url);
            let device_id = agent.register(&client).await;
            let client = Arc::new(Mutex::new((client, device_id)));
            tokio::join!(heartbeat_loop(agent.clone(), client.clone()), delivery_loop(agent, client));
        })
    };

    info!("agent starting");
    let result = platform::run(recorder);

    networking.abort();
    rt.block_on(agent.enqueue(events::agent_stopping(AGENT_VERSION)));
    rt.shutdown_timeout(Duration::from_secs(2));

    result
}
