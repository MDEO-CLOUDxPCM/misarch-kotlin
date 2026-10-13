#!/usr/bin/env bash
# Builds all misarch-kotlin/<service>:dev images, one after the other (Gradle via tools/build.sh: measurement lock,
# RAM check, build slots). Services without a project directory are reported and skipped.
#   deploy/build-images.sh                 # all
#   deploy/build-images.sh order payment   # some
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVICES=("$@")
[ ${#SERVICES[@]} -gt 0 ] || SERVICES=(address catalog discount inventory invoice media notification order payment return review shipment shoppingcart tax user wishlist simulation)
failed=()
for s in "${SERVICES[@]}"; do
  if [ ! -f "$ROOT/services/$s/build.gradle.kts" ]; then echo "skip $s (no services/$s/build.gradle.kts)"; failed+=("$s"); continue; fi
  "$ROOT/tools/image.sh" "$s" || failed+=("$s")
done
[ ${#failed[@]} -eq 0 ] || { echo "not built: ${failed[*]}" >&2; exit 1; }
