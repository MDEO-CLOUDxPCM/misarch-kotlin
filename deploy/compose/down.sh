#!/usr/bin/env bash
# Stop the misarch-kotlin deployment (containers and volumes are kept); --wipe removes containers and volumes.
set -euo pipefail
cd "$(dirname "$0")"
if [ "${1:-}" = "--wipe" ]; then docker compose down -v; else docker compose stop; fi
