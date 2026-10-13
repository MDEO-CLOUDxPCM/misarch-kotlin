#!/usr/bin/env bash
# Run the calibration load generator (misarch-optimization/calibration/scripts/loadgen.py, unchanged) against the
# running stack, with its own state / pool / token files (the calibration's pools.json is never touched).
#   deploy/integration/loadgen.sh kotlin   plans/functional.json results/k_functional
#   deploy/integration/loadgen.sh original plans/functional.json results/o_functional
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CAL="${MISARCH_CALIBRATION:-/home/devbox/workspace/misarch-optimization/calibration}"
STACK="$1"; PLAN="$2"; OUT="$3"; shift 3
case "$STACK" in
  kotlin)   P=misarch-kotlin-; export MISARCH_STATE="${MISARCH_STATE:-$HERE/state/state.json}";;
  original) P=misarch-;        export MISARCH_STATE="${MISARCH_STATE:-$CAL/results/seed/state.json}";;
  *) echo "stack: kotlin|original" >&2; exit 2;;
esac
ip() { docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$1"; }
docker ps --format '{{.Names}}' | grep -q "^${P}gateway-1$" || { echo "${P}gateway-1 is not running" >&2; exit 1; }
export GW_IP=$(ip ${P}gateway-1) KC_IP=$(ip ${P}keycloak-1) HOP_IP=$(ip ${P}shipment-1) USER_IP=$(ip ${P}user-1)
export MISARCH_POOLS="${MISARCH_POOLS:-$HERE/state/pools_$STACK.json}" MISARCH_TOKENS="${MISARCH_TOKENS:-$HERE/state/tokens_$STACK.json}"
cd "$HERE"
exec "$CAL/.venv/bin/python" "$CAL/scripts/loadgen.py" --plan "$PLAN" --out "$OUT" "$@"
