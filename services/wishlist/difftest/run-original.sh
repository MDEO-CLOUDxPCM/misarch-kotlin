#!/usr/bin/env bash
# Runs the ORIGINAL Rust service (ghcr.io/misarch/<svc>:main) with a throwaway mongo:6.0 and replays the scenario.
# usage: run_original.sh shoppingcart|wishlist <outfile>
set -euo pipefail
SVC=$1; OUT=$2
DIR="$(cd "$(dirname "$0")" && pwd)"
LOCK=/home/devbox/workspace/misarch-optimization/MEASURING.lock
if [ -e "$LOCK" ]; then echo "measurement running, refusing" >&2; exit 3; fi
NET=difftest-$SVC-$$
PORT=$((18000 + RANDOM % 1000))
docker network create "$NET" >/dev/null
cleanup() { docker rm -f "dt-$SVC-app-$$" "dt-$SVC-db-$$" >/dev/null 2>&1 || true; docker network rm "$NET" >/dev/null 2>&1 || true; }
trap cleanup EXIT
docker run -d --rm --name "dt-$SVC-db-$$" --network "$NET" --network-alias db --memory 400m mongo:6.0 --quiet >/dev/null
docker run -d --rm --name "dt-$SVC-app-$$" --network "$NET" --memory 200m -p "127.0.0.1:$PORT:8080" \
  -e MONGODB_URI=mongodb://db:27017 -e OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:9 \
  "ghcr.io/misarch/$SVC:main" >/dev/null
for i in $(seq 1 60); do curl -sf "http://127.0.0.1:$PORT/health" >/dev/null && break; sleep 1; done
sleep 3
python3 -I "$DIR/scenario.py" "http://127.0.0.1:$PORT" > "$OUT"
echo "wrote $OUT ($(wc -l < "$OUT") steps)"
