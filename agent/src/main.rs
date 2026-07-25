use std::path::Path;

use chrono::Utc;
use clap::{Parser, Subcommand};

use timelord_agent::agent::{self, AGENT_VERSION};
use timelord_agent::config::{self, CliOverrides};
use timelord_agent::controller_client::ControllerClient;
use timelord_agent::queue::EventQueue;
use timelord_agent::{data_dir, events};

#[derive(Parser)]
#[command(name = "timelord-agent", version = AGENT_VERSION, about = "TimeLord Agent")]
struct Cli {
    #[command(subcommand)]
    command: Option<Command>,
}

#[derive(Subcommand)]
enum Command {
    /// Run interactively in the foreground (development/debugging).
    Run(RunArgs),
    /// Run through the Windows Service Control Manager. This is what the
    /// SCM itself invokes after `install`; you don't normally run it by hand.
    Service,
    /// Install the Windows service (LocalSystem, auto-start).
    Install,
    /// Stop and remove the Windows service.
    Uninstall,
    /// Print resolved configuration and local event-queue status.
    Status,
    /// Run controller discovery once and print what responded.
    Discover(DiscoverArgs),
    /// Enqueue (and attempt to immediately deliver) one synthetic test event.
    TestEvent,
}

#[derive(clap::Args, Default)]
struct RunArgs {
    #[arg(long)]
    controller_url: Option<String>,
    #[arg(long)]
    device_id: Option<String>,
    #[arg(long)]
    device_name: Option<String>,
    #[arg(long)]
    no_discovery: bool,
}

#[derive(clap::Args)]
struct DiscoverArgs {
    /// Also persist the discovered URL to agent.toml, overwriting any
    /// existing value (design brief: "Allow discovery to be forced again
    /// using the CLI" / "Do not overwrite ... unless explicitly requested" —
    /// running this flag *is* the explicit request).
    #[arg(long)]
    force: bool,
}

impl RunArgs {
    fn to_cli_overrides(&self) -> CliOverrides {
        CliOverrides {
            controller_url: self.controller_url.clone(),
            device_id: self.device_id.clone(),
            device_name: self.device_name.clone(),
            discovery_enabled: self.no_discovery.then_some(false),
        }
    }
}

fn main() -> anyhow::Result<()> {
    let cli = Cli::parse();
    let dir = data_dir()?;
    let _log_guard = init_logging(&dir);

    match cli.command {
        Some(Command::Run(args)) => agent::run_blocking(&dir, &args.to_cli_overrides()),
        Some(Command::Service) => run_service(),
        None if cfg!(windows) => run_service(),
        None => agent::run_blocking(&dir, &CliOverrides::default()),
        Some(Command::Install) => install_service(),
        Some(Command::Uninstall) => uninstall_service(),
        Some(Command::Status) => status(&dir),
        Some(Command::Discover(args)) => discover(&dir, args),
        Some(Command::TestEvent) => test_event(&dir),
    }
}

#[cfg(windows)]
fn run_service() -> anyhow::Result<()> {
    timelord_agent::service_win::run()
}

#[cfg(not(windows))]
fn run_service() -> anyhow::Result<()> {
    anyhow::bail!("`service` mode requires the Windows Service Control Manager and only runs on Windows")
}

#[cfg(windows)]
fn install_service() -> anyhow::Result<()> {
    timelord_agent::service_win::install()
}

#[cfg(not(windows))]
fn install_service() -> anyhow::Result<()> {
    anyhow::bail!("service installation is only supported on Windows")
}

#[cfg(windows)]
fn uninstall_service() -> anyhow::Result<()> {
    timelord_agent::service_win::uninstall()
}

#[cfg(not(windows))]
fn uninstall_service() -> anyhow::Result<()> {
    anyhow::bail!("service installation is only supported on Windows")
}

fn status(dir: &Path) -> anyhow::Result<()> {
    let agent_toml_path = dir.join("agent.toml");
    let config = config::load(&agent_toml_path, &CliOverrides::default())?;
    let queue = EventQueue::open(&dir.join("events.db"))?;

    println!("Data directory:       {}", dir.display());
    println!("Device ID:            {}", config.device_id);
    println!("Device name:          {}", config.device_name);
    println!("Controller URL:       {}", config.controller_url.as_deref().unwrap_or("(not configured)"));
    println!("Discovery enabled:    {}", config.discovery_enabled);
    println!("Heartbeat interval:   {}s", config.heartbeat_interval.as_secs());
    println!("Pending local events: {}", queue.len()?);

    #[cfg(windows)]
    {
        println!("Service state:        {}", timelord_agent::service_win::query_status().unwrap_or_else(|e| format!("unknown ({e})")));
    }

    Ok(())
}

fn discover(dir: &Path, args: DiscoverArgs) -> anyhow::Result<()> {
    let agent_toml_path = dir.join("agent.toml");
    let config = config::load(&agent_toml_path, &CliOverrides::default())?;

    let rt = tokio::runtime::Runtime::new()?;
    let found = rt.block_on(timelord_agent::discovery::discover(
        config.device_id,
        AGENT_VERSION,
        &config.device_name,
        config.discovery_port,
        config.discovery_timeout,
    ))?;

    if found.is_empty() {
        println!("No controller responded within {:?}.", config.discovery_timeout);
        return Ok(());
    }

    println!("Found {} controller(s):", found.len());
    for candidate in &found {
        println!(
            "  - {} ({})  priority={}  url={}  response_time={:?}",
            candidate.controller_name, candidate.controller_id, candidate.priority, candidate.controller_url, candidate.response_time
        );
    }

    let best = timelord_agent::discovery::select_best(&found).expect("non-empty");
    println!("\nSelected: {} ({})", best.controller_name, best.controller_url);

    if args.force {
        config::persist_controller_url(&agent_toml_path, &best.controller_url)?;
        println!("Persisted to {}", agent_toml_path.display());
    } else {
        println!("(not persisted — pass --force to save this as the configured controller URL)");
    }

    Ok(())
}

fn test_event(dir: &Path) -> anyhow::Result<()> {
    let agent_toml_path = dir.join("agent.toml");
    let config = config::load(&agent_toml_path, &CliOverrides::default())?;
    let queue = EventQueue::open(&dir.join("events.db"))?;

    let event = events::agent_started(AGENT_VERSION, "CLI_TEST_EVENT");
    let event_id = event.event_id;
    queue.enqueue(&event, Utc::now())?;
    println!("Queued test event {event_id}");

    let Some(controller_url) = config.controller_url.clone() else {
        println!("No controller configured; event will be delivered once one is available.");
        return Ok(());
    };

    let rt = tokio::runtime::Runtime::new()?;
    rt.block_on(async {
        let client = ControllerClient::new(&controller_url);
        let device_id = match client.register(&build_register_request(&config)).await {
            Ok(resp) => resp.device_id,
            Err(err) => {
                println!("Registration failed, event stays queued for later delivery: {err}");
                return anyhow::Ok(());
            }
        };
        let request = timelord_agent::protocol::EventSubmissionRequest { events: vec![event.to_event_item()] };
        match client.submit_events(device_id, &request).await {
            Ok(response) => {
                println!(
                    "Delivered: accepted={} duplicates={} rejected={}",
                    response.accepted, response.duplicates, response.rejected
                );
                if let Some(result) = response.results.first() {
                    if !matches!(result.status, timelord_agent::protocol::EventResultStatus::Rejected) {
                        queue.mark_delivered_by_event_id(event_id)?;
                    }
                }
            }
            Err(err) => println!("Delivery failed, event stays queued for later delivery: {err}"),
        }
        Ok(())
    })?;

    Ok(())
}

fn build_register_request(config: &timelord_agent::config::AgentConfig) -> timelord_agent::protocol::RegisterRequest {
    timelord_agent::protocol::RegisterRequest {
        device_id: config.device_id,
        device_name: config.device_name.clone(),
        hostname: config.device_name.clone(),
        agent_version: AGENT_VERSION.to_string(),
        operating_system: std::env::consts::OS.to_string(),
        operating_system_version: String::new(),
        architecture: std::env::consts::ARCH.to_string(),
        local_ip_addresses: Vec::new(),
    }
}

fn init_logging(dir: &Path) -> tracing_appender::non_blocking::WorkerGuard {
    use tracing_subscriber::{fmt, layer::SubscriberExt, util::SubscriberInitExt, EnvFilter};

    let logs_dir = dir.join("logs");
    let _ = std::fs::create_dir_all(&logs_dir);
    let file_appender = tracing_appender::rolling::daily(&logs_dir, "agent.log");
    let (file_writer, guard) = tracing_appender::non_blocking(file_appender);

    let filter = || EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info"));

    tracing_subscriber::registry()
        .with(filter())
        .with(fmt::layer().with_writer(std::io::stdout))
        .with(fmt::layer().with_writer(file_writer).with_ansi(false))
        .init();

    guard
}
