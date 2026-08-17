# TimeLord Deployment

Packaging and deployment assets:

- `windows/` — agent service installer / uninstaller (`install.ps1`, `uninstall.ps1`),
  shipped in the release zip built by `.github/workflows/release.yml`. See
  `windows/README.md`.
- `docker/` — `redeploy.sh`: pulls the latest images, tears down the running stack (keeping the
  postgres data volume), clears out any leftover container still holding the discovery UDP port
  (a common cause of `up` failing with "port is already allocated"), then brings the stack back
  up. Run from the directory containing `compose.yaml`/`.env`, or pass that directory as `$1`.
  The controller's Dockerfile lives in `controller/Dockerfile`, and the Compose stack (postgres +
  controller) is the top-level `compose.yaml` — see the root README's Quick start.
- `systemd/` — controller systemd unit for bare-metal Linux hosts. Not yet populated.
