#!/usr/bin/env bash
# THE only way to run Gradle in this repository (machine rules: RAM is shared by many agents and a measured MiSArch).
#
#   tools/build.sh :services:address:test
#   tools/build.sh :services:address:bootJar
#
# 1. waits while a measurement window is running (MEASURING.lock of the calibration agent),
# 2. waits while less than MISARCH_BUILD_MIN_MEM_MB (default 1500) MB RAM are available (max. 10 min),
# 3. acquires one of $MISARCH_BUILD_SLOTS (default 2) build slots (flock on tools/.slots/slot<N>.lock),
# 4. runs ./gradlew "$@" while holding the slot.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MEASURING_LOCK="${MISARCH_MEASURING_LOCK:-/home/devbox/workspace/architecture-optimization/MEASURING.lock}"
SLOTS="${MISARCH_BUILD_SLOTS:-2}"
MIN_MEM_MB="${MISARCH_BUILD_MIN_MEM_MB:-1500}"
SLOT_DIR="$ROOT/tools/.slots"
mkdir -p "$SLOT_DIR"

log() { echo "[build.sh $(date +%H:%M:%S)] $*" >&2; }

measuring() {
  [ -e "$MEASURING_LOCK" ] || return 1
  # stale lock: owner pid gone and lock older than 2 h -> ignore (with a warning)
  local pid age
  pid=$(grep -o 'pid [0-9]*' "$MEASURING_LOCK" 2>/dev/null | awk '{print $2}' | head -1 || true)
  age=$(( $(date +%s) - $(stat -c %Y "$MEASURING_LOCK" 2>/dev/null || date +%s) ))
  if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null && [ "$age" -gt 7200 ]; then
    log "WARNING: ignoring stale $MEASURING_LOCK (pid $pid not running, age ${age}s)"
    return 1
  fi
  return 0
}

wait_measuring() {
  local said=0
  while measuring; do
    if [ $said -eq 0 ]; then
      log "measurement window running, waiting: $(head -c 300 "$MEASURING_LOCK" | tr '\n' ' ')"
      said=1
    fi
    sleep 15
  done
}

wait_memory() {
  local waited=0 avail
  while true; do
    avail=$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo)
    [ "$avail" -ge "$MIN_MEM_MB" ] && return 0
    [ $waited -ge 600 ] && { log "WARNING: only ${avail} MB available after 10 min, building anyway"; return 0; }
    [ $waited -eq 0 ] && log "only ${avail} MB RAM available (< ${MIN_MEM_MB}), waiting"
    sleep 15; waited=$((waited + 15))
  done
}

wait_measuring

# acquire a slot (the fd stays open in the exec'd gradlew process -> slot held until Gradle exits)
SLOT=""
said=0
while [ -z "$SLOT" ]; do
  for i in $(seq 1 "$SLOTS"); do
    exec {fd}>"$SLOT_DIR/slot$i.lock"
    if flock -n "$fd"; then SLOT=$i; break; fi
    exec {fd}>&-
  done
  if [ -z "$SLOT" ]; then
    [ $said -eq 0 ] && { log "all $SLOTS build slots busy, waiting"; said=1; }
    sleep 5
  fi
done
echo "pid $$ $(date -Iseconds) $*" >&"$fd"

# a measurement may have started while we waited for a slot
wait_measuring
wait_memory

log "slot $SLOT: ./gradlew $*"
cd "$ROOT"
exec ./gradlew "$@"
