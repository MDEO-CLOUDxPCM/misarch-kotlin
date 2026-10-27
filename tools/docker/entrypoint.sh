#!/bin/sh
# Starts the Spring Boot application extracted into /app (layered jar, launcher mode).
# JAVA_OPTS: extra JVM options (default none = same JVM defaults as the original images).
# MISARCH_OTEL_AGENT=false: start without the OpenTelemetry Java agent.
# MISARCH_PROFILER=<path of libasyncProfiler.so> (e.g. /opt/async-profiler/lib/libasyncProfiler.so, mounted): profile
#   from JVM start with async-profiler, options MISARCH_PROFILER_OPTS (default: itimer every 10 ms, JFR written at
#   exit to /timing/<OTEL_SERVICE_NAME>-<container hostname>-<pid>.jfr - the JVM is pid 1 in every container, so the
#   pid alone would collide in a shared mount; /timing must be writable for uid 10001). Skipped with a warning if the
#   file is missing.
set -e
AGENT=""
if [ "${MISARCH_OTEL_AGENT:-true}" != "false" ]; then
  AGENT="-javaagent:/opt/otel/opentelemetry-javaagent.jar"
fi
PROFILER=""
if [ -n "${MISARCH_PROFILER:-}" ]; then
  if [ -f "$MISARCH_PROFILER" ]; then
    PROFILER_FILE="/timing/${OTEL_SERVICE_NAME:-app}-$(cat /etc/hostname 2>/dev/null || echo host)-%p.jfr"
    PROFILER="-agentpath:${MISARCH_PROFILER}=${MISARCH_PROFILER_OPTS:-start,event=itimer,interval=10ms,file=$PROFILER_FILE}"
  else
    echo "entrypoint: MISARCH_PROFILER=$MISARCH_PROFILER not found, starting without profiler" >&2
  fi
fi
# shellcheck disable=SC2086
exec java $AGENT $PROFILER ${JAVA_OPTS:-} org.springframework.boot.loader.launch.JarLauncher "$@"
