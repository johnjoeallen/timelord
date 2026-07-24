use std::path::Path;

use timelord_agent::{data_dir, platform, UsageRecorder};

fn main() -> anyhow::Result<()> {
    let dir = data_dir()?;
    // Held for the lifetime of `main` — dropping it would stop flushing the
    // background file-log writer. Under the Service Control Manager there is
    // no attached console, so the file under `<dir>/logs/` is the only place
    // logs go once installed as a service; stdout still gets a copy too,
    // which is what `--console` relies on for foreground debugging.
    let _log_guard = init_logging(&dir);

    #[cfg(windows)]
    {
        // `--console` lets a developer run the exact same binary as an
        // ordinary foreground process on a Windows dev box, without going
        // through the Service Control Manager.
        if std::env::args().any(|a| a == "--console") {
            run_console(&dir)
        } else {
            timelord_agent::service_win::run()
        }
    }

    #[cfg(not(windows))]
    {
        // There is no Windows Service Control Manager here; always run in
        // the foreground via the interactive dev backend.
        run_console(&dir)
    }
}

fn run_console(dir: &Path) -> anyhow::Result<()> {
    let db_path = dir.join("state.db");
    tracing::info!(path = %db_path.display(), "opening usage store");
    let recorder = UsageRecorder::open(&db_path)?;
    platform::run(recorder)
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
