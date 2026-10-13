#!/usr/bin/env bash
# Indicative performance smoke: loadgen (plans/perfsmoke.json) + 1 Hz cgroup CPU sampler (calibration cpumon.py),
# then perfsummary.py. Same plan for both stacks; the machine is shared, so the numbers are only an order of magnitude.
#   deploy/integration/perfsmoke.sh kotlin   results/k_perf
#   deploy/integration/perfsmoke.sh original results/o_perf
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CAL="${MISARCH_CALIBRATION:-/home/devbox/workspace/misarch-optimization/calibration}"
STACK="$1"; OUT="$2"
mkdir -p "$HERE/$OUT" 2>/dev/null || mkdir -p "$OUT"
cd "$HERE"
"$CAL/.venv/bin/python" "$CAL/scripts/cpumon.py" --out "$OUT" --duration 900 > "$OUT/cpumon.log" 2>&1 &
MON=$!
"$HERE/loadgen.sh" "$STACK" plans/perfsmoke.json "$OUT" | tee "$OUT/loadgen.log"
kill -INT $MON; wait $MON || true
"$CAL/.venv/bin/python" "$HERE/perfsummary.py" "$OUT" | tee "$OUT/summary.txt"
