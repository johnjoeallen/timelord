//! Per-user notifier state: which messages have already been shown. Kept in
//! the user's own profile (the notifier runs as the logged-on user, not as the
//! service), so one person's seen messages never hide another's.

use std::path::{Path, PathBuf};

use uuid::Uuid;

pub fn default_path() -> PathBuf {
    let base = std::env::var_os("LOCALAPPDATA")
        .or_else(|| std::env::var_os("XDG_STATE_HOME"))
        .map(PathBuf::from)
        .unwrap_or_else(std::env::temp_dir);
    base.join("TimeLord").join("notifier-shown.json")
}

pub fn load_shown(path: &Path) -> Vec<Uuid> {
    std::fs::read_to_string(path).ok().and_then(|text| serde_json::from_str(&text).ok()).unwrap_or_default()
}

/// Temp file + rename so a crash can't leave a truncated file.
pub fn save_shown(path: &Path, ids: &[Uuid]) -> std::io::Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let tmp = path.with_extension("json.tmp");
    std::fs::write(&tmp, serde_json::to_vec(ids)?)?;
    std::fs::rename(tmp, path)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_and_tolerates_missing_or_corrupt_files() {
        let dir = std::env::temp_dir().join(format!("timelord-notifier-test-{}", std::process::id()));
        let path = dir.join("nested").join("shown.json");
        assert!(load_shown(&path).is_empty());

        let ids = vec![Uuid::from_u128(1), Uuid::from_u128(2)];
        save_shown(&path, &ids).unwrap();
        assert_eq!(load_shown(&path), ids);

        std::fs::write(&path, "not json").unwrap();
        assert!(load_shown(&path).is_empty());
        std::fs::remove_dir_all(dir).unwrap();
    }
}
