#!/usr/bin/env bash
# Experiment-server entry point (runner container of the web manager; also safe by hand). Everything it writes goes
# to $RESULTS_DIR (default: build/server-results/<timestamp>-<cmd>). See deploy/server/README.md.
#
#   deploy/server/run.sh check [--campaign]                      preflight table + host.json; exit 1 on FAIL
#   deploy/server/run.sh images [--tag TAG] [--services a,b] [--no-build]
#                                                                build misarch-kotlin/<svc>:dev (+ :TAG), images.json
#   deploy/server/run.sh regime [--minutes 10] [--interval 30]   speed-regime watch, no stack
#   deploy/server/run.sh smoke [--minutes 20] [--tag TAG] [--keep-up] [--build-missing] [--rate 5]
#                                                                stack up, small GraphQL load, 1 Hz cgroup CPU, down
#   deploy/server/run.sh campaign [run-third-pass.sh args...]    tonight's third pass (palladio timing/)
#   deploy/server/run.sh selftest                                cgroup reader vs. a throwaway busybox container
#
# Run it as `exec deploy/server/run.sh ...` (or as the only command of `bash -c`): cancelling sends SIGTERM to the
# top process only, and smoke/regime/campaign clean up on it.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
WS="${WORKSPACE:-/home/devbox/workspace}"
PAL="$WS/misarch-optimization"   # the optimisation repo (formerly palladio-architecture-optimization-domain)
TIM="$PAL/cases/misarch/timing"   # its MiSArch case's timing campaign (since its AB52); the locks stay in $PAL
CAL="$TIM/calibration"
SERVICES="address catalog discount inventory invoice media notification order payment return review shipment shoppingcart tax user wishlist simulation"
cmd="${1:-}"; shift || true
if [ -z "${RESULTS_DIR:-}" ]; then
  RESULTS_DIR="$ROOT/build/server-results/$(date +%Y%m%d-%H%M%S)-${cmd:-none}"
fi
export RESULTS_DIR
mkdir -p "$RESULTS_DIR"
log() { echo "[run.sh $(date +%H:%M:%S)] $*"; }
gitc() { git -c "safe.directory=$ROOT" -C "$ROOT" "$@"; }

images() {
  local tag="" services="$SERVICES" nobuild=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --tag) tag=$2; shift 2 ;;
      --services) services=$(echo "$2" | tr ',' ' '); shift 2 ;;
      --no-build) nobuild=--no-build; shift ;;
      *) echo "unknown option $1" >&2; exit 2 ;;
    esac
  done
  # the Gradle cache of the host user (persists across jobs; HOME is /root in the runner container)
  if [ -z "${GRADLE_USER_HOME:-}" ] && [ -d /home/devbox ]; then export GRADLE_USER_HOME=/home/devbox/.gradle; fi
  # the JDK 25 toolchain is satisfied by the JVM Gradle runs on (JAVA_HOME); foojay only downloads without one
  local jv; jv=$("${JAVA_HOME:+$JAVA_HOME/bin/}java" -version 2>&1 | head -1 || true)
  echo "$jv" | grep -q 'version "25' || log "WARNING: java is not 25 ($jv): Gradle will auto-provision a JDK 25 (foojay)"
  log "images: services [$services] ${nobuild:+(no build) }${tag:+tag :$tag }GRADLE_USER_HOME=${GRADLE_USER_HOME:-~/.gradle} $jv"
  local rc=0
  if [ -n "$nobuild" ]; then
    for s in $services; do "$ROOT/tools/image.sh" "$s" --no-build || rc=1; done
  else
    # shellcheck disable=SC2086
    "$ROOT/deploy/build-images.sh" $services || rc=$?
  fi
  if [ -n "$tag" ]; then
    for s in $services; do
      if docker image inspect "misarch-kotlin/$s:dev" >/dev/null 2>&1; then
        docker tag "misarch-kotlin/$s:dev" "misarch-kotlin/$s:$tag" && log "tagged misarch-kotlin/$s:$tag"
      fi
    done
  fi
  local list=""
  for s in $services; do
    list="$list$s=$(docker image inspect -f '{{.Id}}' "misarch-kotlin/$s:dev" 2>/dev/null || true) "
  done
  python3 - "$RESULTS_DIR/images.json" "$tag" "$(gitc rev-parse HEAD)" "$(gitc rev-parse --abbrev-ref HEAD)" \
    "$(gitc status --porcelain --untracked-files=no | wc -l)" "$rc" $list <<'PYEOF'
import json, sys, time
out, tag, commit, branch, dirty, rc, *pairs = sys.argv[1:]
imgs = {}
for p in pairs:
    s, _, i = p.partition("=")
    imgs[s] = {"dev": f"misarch-kotlin/{s}:dev", "tag": f"misarch-kotlin/{s}:{tag}" if tag and i else None, "id": i or None}
json.dump({"time": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "commit": commit, "branch": branch,
           "trackedFilesChanged": int(dirty), "tag": tag or None, "buildExit": int(rc), "images": imgs},
          open(out, "w"), indent=1)
PYEOF
  log "images.json: $RESULTS_DIR/images.json (build exit $rc)"
  return "$rc"
}

campaign() {
  [ -d "$CAL" ] || { echo "missing $CAL (check out misarch-optimization first)" >&2; exit 1; }
  [ -x "$TIM/run-third-pass.sh" ] || { echo "missing $TIM/run-third-pass.sh" >&2; exit 1; }
  local vrc
  set +e; python3 "$HERE/calvenv.py" "$CAL" 2>&1 | tee "$RESULTS_DIR/calvenv.log"; vrc=${PIPESTATUS[0]}; set -e
  [ "$vrc" = 0 ] || { echo "calibration venv not usable (see calvenv.log)" >&2; exit 1; }
  local marker="$RESULTS_DIR/.started" child rc=0
  touch "$marker"
  log "campaign: $TIM/run-third-pass.sh $* (own process group; outputs collected into $RESULTS_DIR/raw)"
  # own session/process group, so a cancel reaches campaign.py and its children, not just this shell
  ( cd "$PAL/cases/misarch" && exec setsid timing/run-third-pass.sh "$@" ) &
  child=$!
  on_cancel() {
    trap '' TERM INT HUP
    log "cancel: SIGTERM to the campaign's process group"
    kill -TERM -- "-$child" 2>/dev/null || kill -TERM "$child" 2>/dev/null || true
    for _ in 1 2 3 4 5 6 7 8; do kill -0 "$child" 2>/dev/null || break; sleep 1; done
    kill -KILL -- "-$child" 2>/dev/null || true
    local ml="$PAL/MEASURING.lock" pid
    if [ -e "$ml" ]; then   # campaign.py has no signal handler: drop its window lock if its owner is gone
      pid=$(grep -o 'pid [0-9]*' "$ml" 2>/dev/null | awk '{print $2}' | head -1 || true)
      if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then log "removing stale $ml (pid $pid)"; rm -f "$ml"; fi
    fi
    log "timing-up.sh down"
    "$TIM/timing-up.sh" down 2>&1 | tail -2 || true
    collect
    exit 143
  }
  collect() {
    mkdir -p "$RESULTS_DIR/raw"
    ( cd "$TIM/raw" 2>/dev/null && find . -type f -newer "$marker" -print0 ) \
      | rsync -a --from0 --files-from=- "$TIM/raw/" "$RESULTS_DIR/raw/" 2>/dev/null || true
    log "collected $(find "$RESULTS_DIR/raw" -type f | wc -l) new files from timing/raw ($(du -sh "$RESULTS_DIR/raw" | cut -f1))"
  }
  trap on_cancel TERM INT HUP
  wait "$child" || rc=$?
  trap - TERM INT HUP
  collect
  log "campaign exit $rc"
  return "$rc"
}

case "$cmd" in
  check)    exec python3 "$HERE/check.py" "$@" ;;
  regime)   exec python3 "$HERE/regime.py" "$@" ;;
  smoke)    exec python3 "$HERE/smoke.py" "$@" ;;
  selftest) exec python3 "$HERE/common.py" selftest ;;
  images)   set +e; images "$@" 2>&1 | tee -a "$RESULTS_DIR/images.log"; exit "${PIPESTATUS[0]}" ;;
  campaign) campaign "$@" ;;
  *) sed -n '2,16p' "$0" >&2; exit 2 ;;
esac
