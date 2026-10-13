#!/usr/bin/env bash
# Replace running service containers with their current misarch-kotlin/<name>:dev image (after tools/image.sh).
# The app's Dapr and ECS sidecars share its network namespace (network_mode: service:<name>), so they must be
# recreated together with the app, otherwise they keep the old (dead) namespace.
#   deploy/compose/redeploy.sh discount order
set -euo pipefail
cd "$(dirname "$0")"
[ $# -gt 0 ] || { echo "usage: $0 <service>..." >&2; exit 2; }
if [ "${TRACING:-0}" = "1" ]; then export DAPR_CONFIG=dapr-config.yaml; else export DAPR_CONFIG=dapr-config-notrace.yaml; fi
targets=()
for s in "$@"; do targets+=("$s" "$s-dapr" "$s-ecs"); done
docker compose up -d --no-deps --force-recreate "${targets[@]}"
for s in "$@"; do
  for i in $(seq 1 90); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' "misarch-kotlin-$s-1" 2>/dev/null)" = healthy ] && break; sleep 2
  done
  echo "$s: $(docker inspect -f '{{.State.Health.Status}}' "misarch-kotlin-$s-1")"
done
