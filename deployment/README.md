# TimeLord Deployment

Packaging and deployment assets:

- `windows/` — agent service installer / uninstaller (`install.ps1`, `uninstall.ps1`),
  shipped in the release zip built by `.github/workflows/release.yml`. See
  `windows/README.md`.
- `docker/` — reserved for future Docker packaging assets. The controller's Dockerfile lives in
  `controller/Dockerfile`, and the Compose stack (postgres + controller) is the top-level
  `compose.yaml` — see the root README's Quick start.
- `systemd/` — controller systemd unit for bare-metal Linux hosts. Not yet populated.
