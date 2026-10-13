#!/usr/bin/env bash
# Builds the docker image misarch-kotlin/<service>:dev (bootJar via tools/build.sh + slim layered image).
#
#   tools/image.sh address            # build jar (Gradle, slot-limited) and image
#   tools/image.sh address --no-build # image from the existing services/address/build/libs/app.jar
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVICE="${1:?usage: tools/image.sh <service> [--no-build]}"
NO_BUILD="${2:-}"
OTEL_VERSION=$(grep '^opentelemetry-javaagent' "$ROOT/gradle/libs.versions.toml" | sed 's/.*"\(.*\)".*/\1/')
JAVA_VERSION=$(grep '^java = ' "$ROOT/gradle/libs.versions.toml" | sed 's/.*"\(.*\)".*/\1/')
JRE_IMAGE="eclipse-temurin:${JAVA_VERSION}-jre"
CACHE="$ROOT/tools/.cache"
mkdir -p "$CACHE"

[ -d "$ROOT/services/$SERVICE" ] || { echo "no such service: services/$SERVICE" >&2; exit 1; }

if [ "$NO_BUILD" != "--no-build" ]; then
  "$ROOT/tools/build.sh" ":services:$SERVICE:bootJar" --quiet
fi
JAR="$ROOT/services/$SERVICE/build/libs/app.jar"
[ -f "$JAR" ] || { echo "missing $JAR" >&2; exit 1; }

# base image (JRE + OTel agent), built once per JRE/agent version
BASE_TAG="misarch-kotlin/jre-base:dev"
BASE_REV=3   # bump when tools/docker/base.Dockerfile or entrypoint.sh change
BASE_LABEL="${JRE_IMAGE}+otel-${OTEL_VERSION}+rev${BASE_REV}"
if [ "$(docker image inspect -f '{{ index .Config.Labels "misarch.base"}}' "$BASE_TAG" 2>/dev/null || true)" != "$BASE_LABEL" ]; then
  AGENT="$CACHE/opentelemetry-javaagent-${OTEL_VERSION}.jar"
  if [ ! -f "$AGENT" ]; then
    curl -fsSL -o "$AGENT.tmp" \
      "https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/${OTEL_VERSION}/opentelemetry-javaagent-${OTEL_VERSION}.jar"
    mv "$AGENT.tmp" "$AGENT"
  fi
  CTX=$(mktemp -d)
  cp "$AGENT" "$CTX/opentelemetry-javaagent.jar"
  cp "$ROOT/tools/docker/entrypoint.sh" "$CTX/entrypoint.sh"
  docker build -q --build-arg "JRE_IMAGE=$JRE_IMAGE" --label "misarch.base=$BASE_LABEL" \
    -t "$BASE_TAG" -f "$ROOT/tools/docker/base.Dockerfile" "$CTX" >/dev/null
  rm -rf "$CTX"
  echo "built $BASE_TAG ($BASE_LABEL)"
fi

CTX=$(mktemp -d)
cp "$JAR" "$CTX/app.jar"
docker build -q -t "misarch-kotlin/$SERVICE:dev" -f "$ROOT/tools/docker/service.Dockerfile" "$CTX" >/dev/null
rm -rf "$CTX"
docker image ls "misarch-kotlin/$SERVICE:dev" --format '{{.Repository}}:{{.Tag}} {{.Size}}'
