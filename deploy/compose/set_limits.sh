#!/usr/bin/env bash
# CPU limits mirroring MiSArch's Kubernetes deployment (same as the calibration setup's set_limits.sh, for the
# compose project misarch-kotlin). The compose file already sets them at start; this re-applies/changes them.
#   set_limits.sh [k8s|none] [SERVICE=CPUS ...]   e.g. set_limits.sh k8s order=1.0
set -euo pipefail
MODE=${1:-k8s}; shift || true
DOMAIN="address catalog discount inventory invoice media notification order payment return review shipment shoppingcart tax user wishlist"
upd() { docker update --cpus "$2" "misarch-kotlin-$1-1" >/dev/null && echo "misarch-kotlin-$1-1 cpus=$2"; }
if [ "$MODE" = "none" ]; then
  for c in $(docker ps --format '{{.Names}}' | grep '^misarch-kotlin-'); do docker update --cpus 0 "$c" >/dev/null; done
  echo "all limits removed"
else
  for s in $DOMAIN simulation experiment-config; do upd "$s" 0.5; done
  upd gateway 2.4
  upd keycloak 2.0
  for s in $DOMAIN gateway keycloak simulation; do upd "$s-ecs" 2.0; done
fi
for kv in "$@"; do upd "${kv%%=*}" "${kv#*=}"; done
