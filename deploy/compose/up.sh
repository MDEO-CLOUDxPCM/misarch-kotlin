#!/usr/bin/env bash
# Start the misarch-kotlin deployment (compose project `misarch-kotlin`).
#   TRACING=1 ./up.sh -> Dapr tracing on (otel-collector -> zipkin); default: tracing off (measurement mode)
# Refuses to start while the original MiSArch (compose project `misarch`) is running: same host ports and not
# enough RAM for both.
set -euo pipefail
cd "$(dirname "$0")"
if docker ps --format '{{.Names}}' | grep -q '^misarch-gateway-1$'; then
  echo "the original MiSArch (project 'misarch') is running - stop it first" >&2; exit 1
fi
missing=$(for s in address catalog discount inventory invoice media notification order payment return review shipment shoppingcart tax user wishlist simulation; do
  docker image inspect "misarch-kotlin/$s:dev" >/dev/null 2>&1 || echo -n "$s "; done)
[ -z "$missing" ] || { echo "missing images: $missing (../build-images.sh)" >&2; exit 1; }
if [ "${TRACING:-0}" = "1" ]; then export DAPR_CONFIG=dapr-config.yaml; else export DAPR_CONFIG=dapr-config-notrace.yaml; fi
docker compose up -d --remove-orphans
echo "waiting for all containers with a health check to be healthy..."
for i in $(seq 1 120); do
  n=$(docker compose ps --format '{{.Name}} {{.Health}}' | awk '$2!="" && $2!="healthy"' | wc -l)
  [ "$n" = "0" ] && break; sleep 5
done
docker compose ps --format '{{.Name}} {{.State}} {{.Health}}' | awk '$2!="running" || ($3!="" && $3!="healthy")' || true
echo "up (tracing=${TRACING:-0}); gateway http://localhost:8080/graphql, keycloak http://localhost:8081"
