//! Ties config, discovery, the HTTP client, and the local event queue
//! together into the running agent's networking loop (design brief
//! section 17). The synchronous usage-tracking side ([`crate::UsageRecorder`])
//! runs independently on the platform message pump thread; this is the
//! async half that talks to the controller.

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex as StdMutex};
use std::time::Duration;

use chrono::{Local, NaiveTime, Utc};
use tokio::sync::{watch, Mutex, Notify};
use tracing::{error, info, warn};
use uuid::Uuid;

use crate::config::{self, AgentConfig, CliOverrides};
use crate::controller_client::ControllerClient;
use crate::discovery;
use crate::events;
use crate::protocol::{EventSubmissionRequest, HeartbeatRequest, RegisterRequest};
use crate::queue::EventQueue;
use crate::{platform, SessionSnapshot, UsageRecorder};

pub const AGENT_VERSION: &str = env!("CARGO_PKG_VERSION");
const EVENT_BATCH_SIZE: usize = 100;
const RECONNECT_BACKOFF: Duration = Duration::from_secs(15);
const OFFLINE_FALLBACK_CHECK_INTERVAL: Duration = Duration::from_secs(300);
/// Bound on how long shutdown waits for one last delivery attempt of
/// anything still queued (notably `AgentStopping`) before giving up and
/// letting the process exit anyway — the SCM only grants a service a short
/// window to react to `SERVICE_CONTROL_SHUTDOWN` before it's killed, and the
/// event is safe either way since it stays in the durable local queue and
/// goes out on the next successful connection regardless.
const SHUTDOWN_FLUSH_TIMEOUT: Duration = Duration::from_secs(5);

/// Shared handle the CLI's other commands (`status`, `test-event`) also use.
pub struct Agent {
    pub config: AgentConfig,
    pub agent_toml_path: PathBuf,
    pub queue: Arc<Mutex<EventQueue>>,
    connected: AtomicBool,
    session_snapshot: Arc<StdMutex<SessionSnapshot>>,
    shutdown_tx: watch::Sender<bool>,
    flush_notify: Notify,
}

impl Agent {
    pub fn new(
        config: AgentConfig,
        agent_toml_path: PathBuf,
        queue: Arc<Mutex<EventQueue>>,
        session_snapshot: Arc<StdMutex<SessionSnapshot>>,
    ) -> Self {
        let (shutdown_tx, _) = watch::channel(false);
        Self {
            config,
            agent_toml_path,
            queue,
            connected: AtomicBool::new(false),
            session_snapshot,
            shutdown_tx,
            flush_notify: Notify::new(),
        }
    }

    pub fn is_connected(&self) -> bool {
        self.connected.load(Ordering::Relaxed)
    }

    /// Tells every loop watching [`Self::shutdown_signal`] to stop — the
    /// delivery loop makes one immediate final delivery attempt first
    /// instead of waiting out its normal retry interval, since by the time
    /// this is called the process is on its way out.
    pub fn begin_shutdown(&self) {
        let _ = self.shutdown_tx.send(true);
    }

    fn shutdown_signal(&self) -> watch::Receiver<bool> {
        self.shutdown_tx.subscribe()
    }

    /// Wakes `delivery_loop` immediately instead of leaving it to wait out
    /// the rest of `event_retry_interval`. Used for events worth reporting
    /// promptly rather than batched — notably "we're online again"
    /// (registration/reconnection), so the controller/dashboard reflects a
    /// device coming back within moments, not minutes.
    fn request_flush(&self) {
        self.flush_notify.notify_one();
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
            network_interfaces: network_interfaces(),
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
                    self.request_flush();
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

    /// Enqueues a `CONTROLLER_CONNECTED` event only on the rising edge, and
    /// wakes the delivery loop so it (and anything else that piled up while
    /// offline) goes out right away instead of waiting for the next tick.
    async fn note_connected(&self, controller_url: &str) {
        if !self.connected.swap(true, Ordering::Relaxed) {
            self.enqueue(events::controller_connected(controller_url)).await;
            self.request_flush();
        }
    }
}

/// Periodically sends heartbeats. Re-registers on `404 DEVICE_NOT_FOUND`
/// (design brief section 17) and keeps retrying indefinitely otherwise.
/// Exits once [`Agent::begin_shutdown`] is called; a stopping agent has
/// nothing useful left to heartbeat about.
pub async fn heartbeat_loop(agent: Arc<Agent>, client: Arc<Mutex<(ControllerClient, Uuid)>>) {
    let mut interval = tokio::time::interval(agent.config.heartbeat_interval);
    let mut shutdown = agent.shutdown_signal();
    loop {
        tokio::select! {
            _ = interval.tick() => {}
            _ = shutdown.changed() => break,
        }
        let (client_ref, device_id) = {
            let guard = client.lock().await;
            (guard.0.base_url().to_string(), guard.1)
        };
        let (username, session_state) = {
            let snapshot = agent.session_snapshot.lock().unwrap();
            (snapshot.username.clone(), snapshot.state.clone())
        };
        let request = HeartbeatRequest {
            event_id: Uuid::new_v4(),
            occurred_at: Utc::now(),
            agent_version: AGENT_VERSION.to_string(),
            uptime_seconds: 0,
            service_state: "RUNNING".to_string(),
            current_user: username,
            session_state,
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
/// encodes real backoff via `next_attempt_at` — except when
/// [`Agent::request_flush`] wakes it early (on shutdown, or on
/// reconnecting/registering after being offline) so time-sensitive events
/// like `AgentStopping` or `ControllerConnected` go out promptly rather than
/// sitting until the next scheduled tick. Anything that still can't be
/// delivered (controller unreachable) simply stays in the durable local
/// queue, exactly as it would on any other failed attempt, and goes out
/// once the controller is reachable again.
pub async fn delivery_loop(agent: Arc<Agent>, client: Arc<Mutex<(ControllerClient, Uuid)>>) {
    let mut interval = tokio::time::interval(agent.config.event_retry_interval);
    let mut shutdown = agent.shutdown_signal();
    loop {
        tokio::select! {
            _ = interval.tick() => {}
            _ = agent.flush_notify.notified() => {}
            _ = shutdown.changed() => {
                deliver_due_batch(&agent, &client).await;
                break;
            }
        }
        deliver_due_batch(&agent, &client).await;
    }
}

async fn deliver_due_batch(agent: &Arc<Agent>, client: &Arc<Mutex<(ControllerClient, Uuid)>>) {
    let pending = {
        let queue = agent.queue.lock().await;
        match queue.due_batch(Utc::now(), EVENT_BATCH_SIZE) {
            Ok(rows) => rows,
            Err(err) => {
                error!(?err, "failed to read event queue");
                return;
            }
        }
    };
    if pending.is_empty() {
        return;
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

/// While the controller is unreachable, usage policy would otherwise go
/// completely unenforced — so on this fixed schedule, if the agent has no
/// live connection *and* local time falls in the configured offline-fallback
/// window (default 01:00-08:30), it puts the device to sleep. Does nothing
/// once the controller is reachable again; the controller's own schedule
/// takes over from there (once schedules exist to enforce).
pub async fn offline_fallback_loop(agent: Arc<Agent>) {
    if !agent.config.offline_fallback_enabled {
        return;
    }
    // `tokio::time::interval` fires its first tick immediately on creation,
    // not after one period — without `interval_at` here, a freshly started
    // agent that's disconnected during the fallback window would suspend
    // within moments of launch instead of waiting out a full check
    // interval first.
    let mut interval = tokio::time::interval_at(
        tokio::time::Instant::now() + OFFLINE_FALLBACK_CHECK_INTERVAL,
        OFFLINE_FALLBACK_CHECK_INTERVAL,
    );
    let mut shutdown = agent.shutdown_signal();
    loop {
        tokio::select! {
            _ = interval.tick() => {}
            _ = shutdown.changed() => break,
        }
        if agent.is_connected() {
            continue;
        }
        if !is_within_window(Local::now().time(), agent.config.offline_fallback_start, agent.config.offline_fallback_end) {
            continue;
        }

        info!("offline and within fallback window; suspending device");
        agent.enqueue(events::power_action_requested("SUSPEND", "offline fallback window")).await;
        // SetSuspendState blocks until resume, so it must not run on a
        // Tokio worker thread directly.
        match tokio::task::spawn_blocking(platform::suspend).await {
            Ok(Ok(())) => agent.enqueue(events::power_action_completed("SUSPEND")).await,
            Ok(Err(err)) => {
                warn!(?err, "offline fallback suspend failed");
                agent.enqueue(events::power_action_failed("SUSPEND", &err.to_string())).await;
            }
            Err(err) => {
                error!(?err, "offline fallback suspend task panicked");
            }
        }
    }
}

/// Whether `now` falls in `[start, end)`, handling windows that cross
/// midnight (e.g. 22:00-06:00) as well as same-day ones (e.g. 01:00-08:30).
fn is_within_window(now: NaiveTime, start: NaiveTime, end: NaiveTime) -> bool {
    if start <= end {
        now >= start && now < end
    } else {
        now >= start || now < end
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

/// True for link-local addresses: IPv4 APIPA (169.254.0.0/16, RFC 3927) and
/// IPv6 link-local (fe80::/10, RFC 4291). Windows reports one of each per
/// network adapter it enumerates — Wi-Fi, Ethernet, VPN, Hyper-V virtual
/// switches, Bluetooth PAN, disconnected/unconfigured NICs, etc. — which
/// are never reachable from another host and only add noise to the
/// reported address list. Checked manually (rather than relying on
/// `is_link_local`/`is_unicast_link_local`) to avoid depending on exact
/// stdlib stabilization details across the toolchain versions this builds
/// against.
fn is_link_local(ip: &std::net::IpAddr) -> bool {
    match ip {
        std::net::IpAddr::V4(v4) => {
            let o = v4.octets();
            o[0] == 169 && o[1] == 254
        }
        std::net::IpAddr::V6(v6) => (v6.segments()[0] & 0xffc0) == 0xfe80,
    }
}

fn local_ip_addresses() -> Vec<String> {
    match local_ip_address::list_afinet_netifas() {
        Ok(interfaces) => interfaces
            .into_iter()
            .filter(|(_, ip)| !ip.is_loopback() && !is_link_local(ip))
            .map(|(name, ip)| format!("{name}: {ip}"))
            .collect(),
        Err(err) => {
            warn!(?err, "failed to enumerate local IP addresses");
            Vec::new()
        }
    }
}

/// Every network adapter the OS reports, unfiltered — including ones with
/// no routable (or no) address right now — so the dashboard can show the
/// full adapter list (name + MAC) rather than just the ones currently
/// carrying a usable IP (see `local_ip_addresses`, which is what's still
/// used for the at-a-glance summary).
fn network_interfaces() -> Vec<crate::protocol::NetworkInterfaceInfo> {
    use network_interface::{NetworkInterface, NetworkInterfaceConfig};
    match NetworkInterface::show() {
        Ok(mut interfaces) => {
            interfaces.sort_by(|a, b| a.name.cmp(&b.name));
            interfaces
                .into_iter()
                .map(|iface| crate::protocol::NetworkInterfaceInfo {
                    name: iface.name,
                    mac_address: iface.mac_addr,
                    addresses: iface.addr.into_iter().map(|addr| addr.ip().to_string()).collect(),
                })
                .collect()
        }
        Err(err) => {
            warn!(?err, "failed to enumerate network interfaces");
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
    let session_snapshot = recorder.snapshot_handle();
    let agent = Arc::new(Agent::new(config, agent_toml_path, queue.clone(), session_snapshot));

    let rt = tokio::runtime::Runtime::new()?;
    rt.block_on(agent.enqueue(events::agent_started(AGENT_VERSION, "STARTUP")));

    let networking = {
        let agent = agent.clone();
        rt.spawn(async move {
            let url = agent.resolve_controller_url(false).await;
            let client = ControllerClient::new(&url);
            let device_id = agent.register(&client).await;
            let client = Arc::new(Mutex::new((client, device_id)));
            tokio::join!(
                heartbeat_loop(agent.clone(), client.clone()),
                delivery_loop(agent.clone(), client),
                offline_fallback_loop(agent),
            );
        })
    };

    info!("agent starting");
    let result = platform::run(recorder);

    // Queue the shutdown notice, then give the networking task a short,
    // bounded window to make one immediate delivery attempt (rather than
    // just cutting it off and leaving the event to wait out a full retry
    // interval on the next run) before the process actually exits. Whether
    // or not that attempt succeeds, the event is safe: the local queue
    // already has it durably, so `Agent::begin_shutdown` racing the
    // enqueue below is fine either way — worst case this event waits for
    // the *next* delivery tick instead of going out immediately.
    rt.block_on(agent.enqueue(events::agent_stopping(AGENT_VERSION)));
    agent.begin_shutdown();
    if rt.block_on(tokio::time::timeout(SHUTDOWN_FLUSH_TIMEOUT, networking)).is_err() {
        warn!("networking task did not finish flushing within the shutdown grace period");
    }
    rt.shutdown_timeout(Duration::from_secs(2));

    result
}

#[cfg(test)]
mod tests {
    use super::*;

    fn t(h: u32, m: u32) -> NaiveTime {
        NaiveTime::from_hms_opt(h, m, 0).unwrap()
    }

    #[test]
    fn same_day_window_matches_documented_default() {
        let start = t(1, 0);
        let end = t(8, 30);
        assert!(is_within_window(t(1, 0), start, end), "inclusive start");
        assert!(is_within_window(t(4, 0), start, end), "middle of window");
        assert!(!is_within_window(t(8, 30), start, end), "exclusive end");
        assert!(!is_within_window(t(0, 59), start, end), "just before window");
        assert!(!is_within_window(t(12, 0), start, end), "outside window");
    }

    #[test]
    fn overnight_window_crossing_midnight_is_handled() {
        let start = t(22, 0);
        let end = t(6, 0);
        assert!(is_within_window(t(23, 30), start, end), "before midnight");
        assert!(is_within_window(t(0, 30), start, end), "after midnight");
        assert!(is_within_window(t(22, 0), start, end), "inclusive start");
        assert!(!is_within_window(t(6, 0), start, end), "exclusive end");
        assert!(!is_within_window(t(12, 0), start, end), "outside window");
    }
}
