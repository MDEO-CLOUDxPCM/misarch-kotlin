#!/bin/sh
# Starts the Spring Boot application extracted into /app (layered jar, launcher mode).
# JAVA_OPTS: extra JVM options (default none = same JVM defaults as the original images).
# MISARCH_OTEL_AGENT=false: start without the OpenTelemetry Java agent.
set -e
AGENT=""
if [ "${MISARCH_OTEL_AGENT:-true}" != "false" ]; then
  AGENT="-javaagent:/opt/otel/opentelemetry-javaagent.jar"
fi
# shellcheck disable=SC2086
exec java $AGENT ${JAVA_OPTS:-} org.springframework.boot.loader.launch.JarLauncher "$@"
