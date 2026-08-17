#!/usr/bin/env bash
# Redeploys the TimeLord controller stack: pulls the latest images, tears
# down the running stack (the postgres data volume is kept), clears out any
# leftover container still holding the discovery UDP port, then brings the
# stack back up.
#
# Run this from the directory containing compose.yaml and .env (i.e. where
# you normally run `docker compose up`), or pass that directory as $1.
set -euo pipefail

TARGET_DIR="${1:-$(pwd)}"
cd "$TARGET_DIR"

if [ ! -f compose.yaml ]; then
    echo "compose.yaml not found in $(pwd) — run this from the TimeLord deployment directory," >&2
    echo "or pass it as an argument: redeploy.sh /path/to/deployment" >&2
    exit 1
fi

echo "==> Pulling latest images"
sudo docker compose pull

echo "==> Stopping the current stack (postgres data volume is kept)"
sudo docker compose down

# `docker compose down` only removes containers belonging to this project.
# If a previous deployment was ever started under a different project name
# (a different directory, or an explicit -p flag), its containers are
# invisible to the command above and can be left holding the discovery UDP
# port (45821 by default) — which is exactly what "port is already
# allocated" on `up` means. Find and remove anything still bound to it
# rather than leaving the port stuck.
DISCOVERY_PORT="${TIMELORD_DISCOVERY_PORT:-45821}"
STALE="$(sudo docker ps -a --filter "publish=${DISCOVERY_PORT}/udp" --format '{{.ID}} {{.Names}}' || true)"
if [ -n "$STALE" ]; then
    echo "==> Found stale container(s) still holding UDP port ${DISCOVERY_PORT}, removing:"
    echo "$STALE"
    echo "$STALE" | awk '{print $1}' | xargs -r sudo docker rm -f
fi

echo "==> Starting the stack"
sudo docker compose up -d

echo "==> Current state"
sudo docker compose ps
