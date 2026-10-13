#!/usr/bin/env bash
# Runs the PORT image (misarch-kotlin/<svc>:dev) with a throwaway postgres:16-alpine and replays the scenario.
# usage: run_port.sh shoppingcart|wishlist <outfile>
set -euo pipefail
SVC=$1; OUT=$2
DIR="$(cd "$(dirname "$0")" && pwd)"
LOCK=/home/devbox/workspace/misarch-optimization/MEASURING.lock
if [ -e "$LOCK" ]; then echo "measurement running, refusing" >&2; exit 3; fi
NET=difftest-port-$SVC-$$
PORT=$((19000 + RANDOM % 1000))
docker network create "$NET" >/dev/null
cleanup() { docker rm -f "dtp-$SVC-app-$$" "dtp-$SVC-db-$$" >/dev/null 2>&1 || true; docker network rm "$NET" >/dev/null 2>&1 || true; }
trap cleanup EXIT
docker run -d --rm --name "dtp-$SVC-db-$$" --network "$NET" --network-alias db --memory 300m \
  -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB="$SVC" postgres:16-alpine -c fsync=off >/dev/null
for i in $(seq 1 30); do docker exec "dtp-$SVC-db-$$" pg_isready -U postgres -d "$SVC" >/dev/null 2>&1 && break; sleep 1; done
sleep 2
docker run -d --rm --name "dtp-$SVC-app-$$" --network "$NET" --memory 700m -p "127.0.0.1:$PORT:8080" \
  -e SPRING_R2DBC_URL="r2dbc:postgresql://db:5432/$SVC" -e SPRING_FLYWAY_URL="jdbc:postgresql://db:5432/$SVC" \
  -e SPRING_R2DBC_USERNAME=postgres -e SPRING_R2DBC_PASSWORD=postgres -e MISARCH_OTEL_AGENT=false \
  "misarch-kotlin/$SVC:dev" >/dev/null
for i in $(seq 1 90); do curl -sf "http://127.0.0.1:$PORT/health" >/dev/null && break; sleep 1; done
sleep 1
python3 -I "$DIR/scenario.py" "http://127.0.0.1:$PORT" > "$OUT"
docker logs "dtp-$SVC-app-$$" > "$OUT.log" 2>&1 || true
echo "wrote $OUT ($(wc -l < "$OUT") steps)"
