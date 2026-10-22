//! Best-effort log file for the notifier. It runs as a background (no console)
//! program, so this is the only place problems can be seen:
//! `%LOCALAPPDATA%\TimeLord\notifier.log`.

use std::io::Write;
use std::path::{Path, PathBuf};

/// Past this size the file is rotated to `notifier.log.old`, keeping disk use bounded.
const MAX_BYTES: u64 = 256 * 1024;

pub fn path() -> PathBuf {
    crate::state::default_path().with_file_name("notifier.log")
}

pub fn log(message: &str) {
    eprintln!("{message}");
    let _ = append(&path(), message, MAX_BYTES);
}

fn append(path: &Path, message: &str, max_bytes: u64) -> std::io::Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    if std::fs::metadata(path).map(|m| m.len() > max_bytes).unwrap_or(false) {
        let _ = std::fs::rename(path, path.with_extension("log.old"));
    }
    let mut file = std::fs::OpenOptions::new().create(true).append(true).open(path)?;
    writeln!(file, "{} {message}", chrono::Local::now().format("%Y-%m-%d %H:%M:%S"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn appends_and_rotates_when_too_big() {
        let dir = std::env::temp_dir().join(format!("timelord-notifier-log-{}", std::process::id()));
        let path = dir.join("notifier.log");

        append(&path, "first", 1024).unwrap();
        append(&path, "second", 1024).unwrap();
        let text = std::fs::read_to_string(&path).unwrap();
        assert!(text.contains("first") && text.contains("second"));

        append(&path, "after-rotation", 4).unwrap();
        let rotated = std::fs::read_to_string(path.with_extension("log.old")).unwrap();
        assert!(rotated.contains("second"));
        assert!(std::fs::read_to_string(&path).unwrap().contains("after-rotation"));
        std::fs::remove_dir_all(dir).unwrap();
    }
}
