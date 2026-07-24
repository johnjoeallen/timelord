# TimeLord Deployment

Packaging and deployment assets:

- `windows/` — agent service installer / uninstaller (`install.ps1`, `uninstall.ps1`),
  shipped in the release zip built by `.github/workflows/release.yml`. See
  `windows/README.md`.
- `docker/` — controller Dockerfile and Compose stack. Not yet populated — no controller yet.
- `systemd/` — controller systemd unit for bare-metal Linux hosts. Not yet populated.
