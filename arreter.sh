#!/usr/bin/env bash
set -eu
cd "$(dirname "$0")"
bash scripts/stop-app.sh
docker compose stop
echo "Arrêt terminé. Les données sont conservées."
