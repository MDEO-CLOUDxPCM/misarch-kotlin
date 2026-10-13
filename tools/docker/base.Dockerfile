# Shared base image of all misarch-kotlin services: JRE of the chosen JDK LTS + OpenTelemetry Java agent
# (the original Kotlin services run with the agent; switch it off with MISARCH_OTEL_AGENT=false).
ARG JRE_IMAGE=eclipse-temurin:25-jre
FROM ${JRE_IMAGE}
# wget and curl: the original compose files' health checks use either (`wget -qO - .../health`, and
# `curl -f -X GET .../health` for simulation), so the images stay drop-in for the original deployment
RUN apt-get update && apt-get install -y --no-install-recommends wget curl && rm -rf /var/lib/apt/lists/*
COPY opentelemetry-javaagent.jar /opt/otel/opentelemetry-javaagent.jar
COPY entrypoint.sh /opt/misarch/entrypoint.sh
RUN chmod 0755 /opt/misarch/entrypoint.sh && useradd --system --uid 10001 --home /app misarch \
    && mkdir -p /app && chown misarch /app
WORKDIR /app
USER misarch
EXPOSE 8080
ENTRYPOINT ["/opt/misarch/entrypoint.sh"]
