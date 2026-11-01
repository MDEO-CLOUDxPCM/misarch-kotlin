#!/usr/bin/env bash
# Semantic SDL diff of a service against the original:
#   tools/sdl-diff.sh <service> [--no-descriptions] [--schemas]
#
# Runs the service's *SdlTest (:services:<service>:sdlTest via tools/build.sh): it starts the application without
# database, queries `{ _service { sdl } }` and writes
#   services/<service>/build/sdl/<service>.graphql                      (raw SDL of the service)
#   services/<service>/build/sdl/<service>.actual.canonical.graphql     (canonical form, see SdlCanonicalizer)
#   services/<service>/build/sdl/<service>.reference.canonical.graphql  (canonical form of the reference)
# and prints `diff -u reference actual` (empty = semantically identical; ordering/whitespace/federation boilerplate
# are ignored). Reference: reference/sdl/<service>.graphql (live `_service { sdl }` of the original), with
# --schemas reference/schemas/<service>.graphql (misarch/schemas repo, what the gateway composes).
# --no-descriptions ignores descriptions. Exit code 0 = no difference.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVICE="${1:?usage: tools/sdl-diff.sh <service> [--no-descriptions] [--schemas]}"
shift
ARGS=()
for a in "$@"; do
  case "$a" in
    --no-descriptions) ARGS+=("-Pmisarch.sdl.descriptions=false") ;;
    --schemas) ARGS+=("-Pmisarch.sdl.reference=schemas") ;;
    *) echo "unknown option $a" >&2; exit 2 ;;
  esac
done
OUT="$ROOT/services/$SERVICE/build/sdl"
mkdir -p "$ROOT/services/$SERVICE/build"
rm -f "$OUT/$SERVICE".*canonical.graphql
"$ROOT/tools/build.sh" ":services:$SERVICE:sdlTest" --quiet "${ARGS[@]}" > "$ROOT/services/$SERVICE/build/sdl-test.log" 2>&1
STATUS=$?
if [ ! -f "$OUT/$SERVICE.actual.canonical.graphql" ]; then
  echo "SDL test did not produce $OUT/$SERVICE.actual.canonical.graphql (exit $STATUS); log:" >&2
  grep -vE "^\s+at " "$ROOT/services/$SERVICE/build/sdl-test.log" | tail -40 >&2
  exit 2
fi
REF_LABEL=$(head -1 "$OUT/$SERVICE.reference.canonical.graphql" | sed 's/^# from //')
if diff -u --label "reference: $REF_LABEL" --label "service: $OUT/$SERVICE.graphql" \
    <(tail -n +2 "$OUT/$SERVICE.reference.canonical.graphql") <(tail -n +2 "$OUT/$SERVICE.actual.canonical.graphql"); then
  echo "SDL of $SERVICE: no semantic difference to $REF_LABEL"
  exit 0
fi
exit 1
