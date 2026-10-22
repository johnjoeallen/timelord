//! Ties config, discovery, the HTTP client, and the local event queue
//! together into the running agent's networking loop (design brief
//! section 17). The synchronous usage-tracking side ([`crate::UsageRecorder`])
//! runs independently on the platform message pump thread; this is the
//! async half that talks to the controller.

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex as StdMutex};
use std::time::Duration;

use chrono::Utc;
use tokio::sync::{watch, Mutex, Notify};
use tracing::{error, info, warn};
use uuid::Uuid;

use crate::config::{self, AgentConfig, CliOverrides};
use crate::controller_client::ControllerClient;
use crate::discovery;
use crate::events;
use crate::messages::{self, MessageStore};
use crate::protocol::{EventSubmissionRequest, HeartbeatRequest, RegisterRequest};
use crate::queue::EventQueue;
use crate::schedule::{self, Schedule};
use crate::{platform, SessionSnapshot, UsageRecorder};

pub const AGENT_VERSION: &str = env!("CARGO_PKG_VERSION");
const EVENT_BATCH_SIZE: usize = 100;
const RECONNECT_BACKOFF: Duration = Duration::from_secs(15);
/// After startup, or after resuming from sleep, how long enforcement waits for the controller to
/// confirm the cached schedule before enforcing the cache regardless (see `schedule::enforcement_ready`).
const SCHEDULE_SYNC_GRACE: Duration = Duration::from_secs(90);
/// A wake-up this much later than planned means the machine slept (or the clock jumped), so the
/// cached schedule can no longer be trusted until the controller confirms it.
const SLEEP_DETECT_SLACK: Duration = Duration::from_secs(30);
/// How long the controller may hold a schedule long-poll open before answering "no change".
const SCHEDULE_POLL_WAIT: Duration = Duration::from_secs(30);
/// Upper bound between enforcement checks, so a device woken mid-window is put back to sleep promptly.
const ENFORCEMENT_MAX_WAIT: Duration = Duration::from_secs(60);
/// After asking Windows to shut down, don't ask again for this long (the shutdown has a grace period).
const SHUTDOWN_COOLDOWN: Duration = Duration::from_secs(300);
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
    schedule: StdMutex<Option<Schedule>>,
    schedule_path: PathBuf,
    schedule_changed: Notify,
    messages: StdMutex<MessageStore>,
    messages_path: PathBuf,
    schedule_sync: StdMutex<ScheduleSync>,
}

/// Whether the cached schedule has been confirmed by the controller since startup / last resume.
struct ScheduleSync {
    confirmed: bool,
    since: std::time::Instant,
}

impl Agent {
    pub fn new(
        config: AgentConfig,
        agent_toml_path: PathBuf,
        queue: Arc<Mutex<EventQueue>>,
        session_snapshot: Arc<StdMutex<SessionSnapshot>>,
    ) -> Self {
        let (shutdown_tx, _) = watch::channel(false);
        let schedule_path = agent_toml_path.with_file_name("schedule.json");
        let schedule = StdMutex::new(schedule::load(&schedule_path));
        let messages_path = agent_toml_path.with_file_name("messages.json");
        let messages = StdMutex::new(messages::load(&messages_path));
        Self {
            config,
            agent_toml_path,
            queue,
            connected: AtomicBool::new(false),
            session_snapshot,
            shutdown_tx,
            flush_notify: Notify::new(),
            schedule,
            schedule_path,
            schedule_changed: Notify::new(),
            messages,
            messages_path,
            schedule_sync: StdMutex::new(ScheduleSync {
                confirmed: false,
                since: std::time::Instant::now(),
            }),
        }
    }

    /// The currently cached schedule, if the device has a calendar.
    pub fn schedule(&self) -> Option<Schedule> {
        self.schedule.lock().unwrap().clone()
    }

    fn schedule_revision(&self) -> Option<String> {
        self.schedule
            .lock()
            .unwrap()
            .as_ref()
            .map(|s| s.revision.clone())
    }

    /// Records that the controller has just confirmed the schedule (including "no calendar"), so the
    /// cache is now safe to enforce. Wakes the enforcement loop if it was holding back.
    fn mark_schedule_synced(&self) {
        let mut sync = self.schedule_sync.lock().unwrap();
        if !sync.confirmed {
            sync.confirmed = true;
            self.schedule_changed.notify_one();
        }
    }

    /// The cache may be stale again (the machine slept, or we just put it to sleep): hold off
    /// enforcing it until the controller confirms it, or the grace period runs out.
    fn invalidate_schedule_sync(&self, reason: &str) {
        info!(
            reason,
            "cached schedule needs re-confirming with the controller before it is enforced"
        );
        let mut sync = self.schedule_sync.lock().unwrap();
        sync.confirmed = false;
        sync.since = std::time::Instant::now();
    }

    /// The schedule to enforce right now (`None` while waiting for the controller to confirm the
    /// cache), plus how much longer that hold lasts, so the enforcement loop knows when to look again.
    fn enforcement_view(&self, grace: Duration) -> (Option<Schedule>, Option<Duration>) {
        let (confirmed, unsynced_for) = {
            let sync = self.schedule_sync.lock().unwrap();
            (sync.confirmed, sync.since.elapsed())
        };
        if schedule::enforcement_ready(confirmed, unsynced_for, grace) {
            (self.schedule(), None)
        } else {
            (None, Some(grace.saturating_sub(unsynced_for)))
        }
    }

    /// The schedule to *announce* to the notifier: the same gate as enforcement, so a stale cache
    /// doesn't produce a warning that disappears the moment the controller confirms otherwise.
    pub fn schedule_for_notices(&self) -> Option<Schedule> {
        self.enforcement_view(SCHEDULE_SYNC_GRACE).0
    }

    /// Adopts a schedule from the controller (`None` = calendar removed), persists it, and wakes the
    /// enforcement loop. A no-op if nothing changed.
    fn apply_schedule(&self, incoming: Option<Schedule>) {
        {
            let mut current = self.schedule.lock().unwrap();
            if *current == incoming {
                return;
            }
            *current = incoming.clone();
        }
        match incoming.as_ref() {
            Some(s) => {
                let now = Utc::now();
                info!(
                    revision = s.revision.as_str(),
                    windows = s.windows.len(),
                    in_window = ?s.active_window(now).map(|w| (w.off_at, w.on_at)),
                    next_off_utc = ?s.next_off_at(now),
                    horizon_ends_utc = ?s.windows.last().map(|w| w.on_at),
                    "schedule updated"
                );
            }
            None => info!("schedule updated: no calendar assigned"),
        }
        if let Err(err) = schedule::save(&self.schedule_path, incoming.as_ref()) {
            warn!(?err, "failed to cache schedule locally");
        }
        self.schedule_changed.notify_one();
    }

    /// Administrator messages still worth showing (unexpired).
    pub fn live_messages(&self) -> Vec<crate::protocol::AgentMessage> {
        self.messages.lock().unwrap().live(Utc::now())
    }

    fn messages_cursor(&self) -> i64 {
        self.messages.lock().unwrap().last_seq
    }

    fn apply_messages(&self, incoming: Vec<crate::protocol::AgentMessage>) {
        let mut store = self.messages.lock().unwrap();
        if store.apply(incoming, Utc::now()) {
            if let Err(err) = messages::save(&self.messages_path, &store) {
                warn!(?err, "failed to cache messages locally");
            }
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
            info!(
                port = self.config.discovery_port,
                "starting controller discovery"
            );
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
                    self.enqueue(events::controller_discovered(
                        best.controller_id,
                        &best.controller_name,
                        &best.controller_url,
                    ))
                    .await;
                    if let Err(err) =
                        config::persist_controller_url(&self.agent_toml_path, &best.controller_url)
                    {
                        warn!(?err, "failed to persist discovered controller URL");
                    }
                    return best.controller_url;
                }
                Ok(_) => {
                    warn!("no controller responded to discovery");
                    self.enqueue(events::controller_discovery_failed(
                        "no controller responded",
                    ))
                    .await;
                }
                Err(err) => {
                    warn!(?err, "controller discovery failed");
                    self.enqueue(events::controller_discovery_failed(&err.to_string()))
                        .await;
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
                    self.enqueue(events::registration_succeeded(response.device_id))
                        .await;
                    self.connected.store(true, Ordering::Relaxed);
                    self.apply_schedule(response.schedule.clone());
                    self.mark_schedule_synced();
                    self.request_flush();
                    return response.device_id;
                }
                Err(err) => {
                    warn!(?err, "registration failed, retrying");
                    self.enqueue(events::registration_failed(&err.to_string()))
                        .await;
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
            self.enqueue(events::controller_connected(controller_url))
                .await;
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
            Ok(response) => {
                agent.note_connected(&client_ref).await;
                agent.apply_schedule(response.schedule);
                agent.mark_schedule_synced();
                agent.enqueue(events::heartbeat_sent()).await;
            }
            Err(err) if err.is_device_not_found() => {
                warn!("controller no longer recognises this device; re-registering");
                agent
                    .note_disconnected("device not found; re-registering")
                    .await;
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

    let request = EventSubmissionRequest {
        events: pending.iter().map(|p| p.event.to_event_item()).collect(),
    };
    let (submit_result, base_url) = {
        let guard = client.lock().await;
        (
            guard.0.submit_events(guard.1, &request).await,
            guard.0.base_url().to_string(),
        )
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
                        if let Err(err) =
                            queue.mark_failed(delivery.row_id, delivery.attempt_count, Utc::now())
                        {
                            error!(?err, "failed to mark event failed");
                        }
                    }
                }
            }
        }
        Err(err) => {
            warn!(
                ?err,
                batch_size = pending.len(),
                "event submission failed; will retry"
            );
            agent.note_disconnected(&err.to_string()).await;
            let queue = agent.queue.lock().await;
            for delivery in &pending {
                if let Err(err) =
                    queue.mark_failed(delivery.row_id, delivery.attempt_count, Utc::now())
                {
                    error!(?err, "failed to mark event failed");
                }
            }
        }
    }
}

/// Long-polls the controller for schedule changes so an extension (or calendar edit) reaches the agent
/// within moments rather than at the next heartbeat. Uses its own client so a parked request never
/// holds the heartbeat loop's lock.
pub async fn schedule_poll_loop(agent: Arc<Agent>, client: Arc<Mutex<(ControllerClient, Uuid)>>) {
    let mut shutdown = agent.shutdown_signal();
    loop {
        let (url, device_id) = {
            let guard = client.lock().await;
            (guard.0.base_url().to_string(), guard.1)
        };
        let poll_client = ControllerClient::new(url);
        let revision = agent.schedule_revision();
        let result = tokio::select! {
            r = poll_client.poll_schedule(device_id, revision.as_deref(), SCHEDULE_POLL_WAIT) => r,
            _ = shutdown.changed() => break,
        };
        match result {
            Ok(schedule) => {
                agent.apply_schedule(schedule);
                agent.mark_schedule_synced();
            }
            Err(err) => {
                // Not-found is the heartbeat loop's job to fix (re-register); either way, back off.
                if !err.is_device_not_found() {
                    warn!(?err, "schedule poll failed");
                }
                tokio::select! {
                    _ = tokio::time::sleep(RECONNECT_BACKOFF) => {}
                    _ = shutdown.changed() => break,
                }
            }
        }
    }
}

/// Long-polls the controller for administrator messages, resuming from the cursor in the local cache
/// so nothing is fetched twice and nothing is lost across restarts. Own client, like the schedule poll.
pub async fn message_poll_loop(agent: Arc<Agent>, client: Arc<Mutex<(ControllerClient, Uuid)>>) {
    let mut shutdown = agent.shutdown_signal();
    loop {
        let (url, device_id) = {
            let guard = client.lock().await;
            (guard.0.base_url().to_string(), guard.1)
        };
        let poll_client = ControllerClient::new(url);
        let result = tokio::select! {
            r = poll_client.poll_messages(device_id, agent.messages_cursor(), SCHEDULE_POLL_WAIT) => r,
            _ = shutdown.changed() => break,
        };
        match result {
            Ok(incoming) => agent.apply_messages(incoming),
            Err(err) => {
                if !err.is_device_not_found() {
                    warn!(?err, "message poll failed");
                }
                tokio::select! {
                    _ = tokio::time::sleep(RECONNECT_BACKOFF) => {}
                    _ = shutdown.changed() => break,
                }
            }
        }
    }
}

/// Enforces the cached calendar schedule, including while the controller is unreachable: whenever
/// `now` is inside an off window, performs the schedule's action (sleep or shutdown). Wakes on a
/// schedule change, the next window start, or [`ENFORCEMENT_MAX_WAIT`], whichever is soonest.
pub async fn enforcement_loop(agent: Arc<Agent>) {
    let mut shutdown = agent.shutdown_signal();
    let mut last_shutdown_request: Option<tokio::time::Instant> = None;
    loop {
        let now = Utc::now();
        let mut wait = ENFORCEMENT_MAX_WAIT;
        // At startup (and after any sleep) the cache may predate a calendar edit or an extension, so
        // it is only enforced once the controller has confirmed it, or the controller stays silent.
        let (schedule, held_for) = agent.enforcement_view(SCHEDULE_SYNC_GRACE);
        if let Some(hold) = held_for {
            wait = wait.min(hold);
        }
        if let Some(schedule) = schedule {
            if let Some(window) = schedule.active_window(now) {
                info!(revision = schedule.revision.as_str(), off_at = %window.off_at, on_at = %window.on_at, "inside an off window");
                enforce(&agent, &schedule, &mut last_shutdown_request).await;
                if schedule.action == schedule::PowerAction::Sleep {
                    // We just slept and have resumed: whatever changed meanwhile must be fetched first.
                    agent.invalidate_schedule_sync("resumed after calendar sleep");
                }
            } else if let Some(next) = schedule.next_off_at(now) {
                wait = wait.min((next - now).to_std().unwrap_or(Duration::ZERO));
            }
        }
        let planned_wake = Utc::now() + chrono::Duration::from_std(wait).unwrap_or_default();
        tokio::select! {
            _ = tokio::time::sleep(wait) => {}
            _ = agent.schedule_changed.notified() => {}
            _ = shutdown.changed() => break,
        }
        // Woke much later than planned: the machine slept (e.g. the lid was closed), so the cache
        // may now be stale even though we didn't cause it.
        let overslept = Utc::now() - planned_wake;
        if overslept
            .to_std()
            .is_ok_and(|late| late > SLEEP_DETECT_SLACK)
        {
            agent.invalidate_schedule_sync("system appears to have slept");
        }
    }
}

async fn enforce(
    agent: &Agent,
    schedule: &Schedule,
    last_shutdown_request: &mut Option<tokio::time::Instant>,
) {
    let action = schedule.action.event_name();
    match schedule.action {
        schedule::PowerAction::Sleep => {
            info!("within calendar off window; suspending device");
            agent
                .enqueue(events::power_action_requested(
                    action,
                    "calendar off window",
                ))
                .await;
            // SetSuspendState blocks until resume, so it must not run on a Tokio worker thread.
            match tokio::task::spawn_blocking(platform::suspend).await {
                Ok(Ok(())) => agent.enqueue(events::power_action_completed(action)).await,
                Ok(Err(err)) => {
                    warn!(?err, "calendar suspend failed");
                    agent
                        .enqueue(events::power_action_failed(action, &err.to_string()))
                        .await;
                }
                Err(err) => error!(?err, "calendar suspend task panicked"),
            }
        }
        schedule::PowerAction::Shutdown => {
            if last_shutdown_request.is_some_and(|t| t.elapsed() < SHUTDOWN_COOLDOWN) {
                return;
            }
            info!("within calendar off window; shutting device down");
            agent
                .enqueue(events::power_action_requested(
                    action,
                    "calendar off window",
                ))
                .await;
            *last_shutdown_request = Some(tokio::time::Instant::now());
            match tokio::task::spawn_blocking(platform::shutdown).await {
                Ok(Ok(())) => agent.enqueue(events::power_action_completed(action)).await,
                Ok(Err(err)) => {
                    warn!(?err, "calendar shutdown failed");
                    agent
                        .enqueue(events::power_action_failed(action, &err.to_string()))
                        .await;
                }
                Err(err) => error!(?err, "calendar shutdown task panicked"),
            }
            // Flush now: the machine may be gone before the next delivery tick.
            agent.request_flush();
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
                    addresses: iface
                        .addr
                        .into_iter()
                        .map(|addr| addr.ip().to_string())
                        .collect(),
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
    let agent = Arc::new(Agent::new(
        config,
        agent_toml_path,
        queue.clone(),
        session_snapshot,
    ));

    let rt = tokio::runtime::Runtime::new()?;
    rt.block_on(agent.enqueue(events::agent_started(AGENT_VERSION, "STARTUP")));
    rt.block_on(agent.enqueue(events::system_boot()));

    let networking = {
        let agent = agent.clone();
        rt.spawn(async move {
            let url = agent.resolve_controller_url(false).await;
            let client = ControllerClient::new(&url);
            let device_id = agent.register(&client).await;
            let client = Arc::new(Mutex::new((client, device_id)));
            tokio::join!(
                heartbeat_loop(agent.clone(), client.clone()),
                schedule_poll_loop(agent.clone(), client.clone()),
                message_poll_loop(agent.clone(), client.clone()),
                delivery_loop(agent.clone(), client),
                enforcement_loop(agent.clone()),
                crate::notify_pipe::serve(agent),
            );
        })
    };

    info!("agent starting");
    let result = platform::run(recorder);

    if platform::shutdown_requested() {
        rt.block_on(agent.enqueue(events::system_shutdown()));
    }

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
    if rt
        .block_on(tokio::time::timeout(SHUTDOWN_FLUSH_TIMEOUT, networking))
        .is_err()
    {
        warn!("networking task did not finish flushing within the shutdown grace period");
    }
    rt.shutdown_timeout(Duration::from_secs(2));

    result
}

#[cfg(test)]
mod tests {
    use super::*;

    fn agent_with_cached_schedule(
        dir: &std::path::Path,
        windows: Vec<schedule::OffWindow>,
    ) -> Agent {
        let cached = Schedule {
            revision: "cached".into(),
            action: schedule::PowerAction::Sleep,
            timezone: "UTC".into(),
            windows,
        };
        schedule::save(&dir.join("schedule.json"), Some(&cached)).unwrap();
        let toml = dir.join("agent.toml");
        let config = config::load(&toml, &CliOverrides::default()).unwrap();
        Agent::new(
            config,
            toml,
            Arc::new(Mutex::new(EventQueue::open_in_memory().unwrap())),
            Arc::new(StdMutex::new(SessionSnapshot::default())),
        )
    }

    fn open_window() -> schedule::OffWindow {
        schedule::OffWindow {
            off_at: Utc::now() - chrono::Duration::hours(1),
            on_at: Utc::now() + chrono::Duration::hours(1),
        }
    }

    #[test]
    fn startup_does_not_enforce_the_cached_schedule_until_the_controller_confirms_it() {
        let dir = tempfile::tempdir().unwrap();
        let agent = agent_with_cached_schedule(dir.path(), vec![open_window()]);
        assert!(agent.schedule().is_some(), "the cache is loaded");

        let (enforceable, held_for) = agent.enforcement_view(Duration::from_secs(90));
        assert!(
            enforceable.is_none(),
            "but not enforced yet: it may predate a calendar edit or extension"
        );
        assert!(held_for.is_some());

        // The controller answers with a newer schedule that has no window now (extension granted).
        agent.apply_schedule(Some(Schedule {
            revision: "fresh".into(),
            action: schedule::PowerAction::Sleep,
            timezone: "UTC".into(),
            windows: vec![],
        }));
        agent.mark_schedule_synced();

        let (enforceable, held_for) = agent.enforcement_view(Duration::from_secs(90));
        assert_eq!(enforceable.unwrap().revision, "fresh");
        assert!(held_for.is_none());
    }

    #[test]
    fn an_unreachable_controller_falls_back_to_the_cache_after_the_grace_period() {
        let dir = tempfile::tempdir().unwrap();
        let agent = agent_with_cached_schedule(dir.path(), vec![open_window()]);

        let (enforceable, _) = agent.enforcement_view(Duration::ZERO);

        assert_eq!(enforceable.unwrap().revision, "cached");
    }

    #[test]
    fn resuming_from_sleep_makes_the_schedule_need_confirming_again() {
        let dir = tempfile::tempdir().unwrap();
        let agent = agent_with_cached_schedule(dir.path(), vec![open_window()]);
        agent.mark_schedule_synced();
        assert!(agent.enforcement_view(Duration::from_secs(90)).0.is_some());

        agent.invalidate_schedule_sync("test resume");

        let (enforceable, held_for) = agent.enforcement_view(Duration::from_secs(90));
        assert!(enforceable.is_none() && held_for.is_some());
        agent.mark_schedule_synced();
        assert!(agent.enforcement_view(Duration::from_secs(90)).0.is_some());
    }

    #[test]
    fn a_controller_saying_no_calendar_also_counts_as_confirmation_and_clears_the_cache() {
        let dir = tempfile::tempdir().unwrap();
        let agent = agent_with_cached_schedule(dir.path(), vec![open_window()]);

        agent.apply_schedule(None);
        agent.mark_schedule_synced();

        assert!(agent.enforcement_view(Duration::from_secs(90)).0.is_none());
        assert!(
            schedule::load(&dir.path().join("schedule.json")).is_none(),
            "cache cleared on disk"
        );
    }
}
