#!/usr/bin/env python3
"""Generate deploy/compose/docker-compose.yaml for misarch-kotlin.

Derived from the calibration compose of the original MiSArch (read-only input):
  /home/devbox/workspace/architecture-optimization/calibration/compose/docker-compose.yaml
Same topology (gateway, Keycloak, Redis pub/sub, Dapr placement, every app with its Dapr sidecar and
experiment-config sidecar (ECS), experiment-config, simulation + RabbitMQ, otel-collector, Zipkin), same CPU limits,
same relaxed health-check intervals, same host ports. Changes:
  * compose project `misarch-kotlin` (own containers, network and volumes; never run it together with the original:
    same host ports 8080 gateway, 8081 keycloak, 3510 user-dapr, 6379 redis)
  * the 16 domain services and simulation use the images misarch-kotlin/<name>:dev (tools/image.sh <name>)
  * former MongoDB services (inventory, invoice, order, payment, review, shoppingcart, wishlist) get a Postgres 16
    `<name>-db` instead of their MongoDB and the Spring env vars (SPRING_R2DBC_URL, ...); inventory-mongosetup is gone
  * media keeps object storage: `media-minio` = RustFS 1.0.1 (S3-compatible; MinIO's community server is no longer
    distributed), same host name and credentials as the original, no database; media waits for its health check
  * Node.js-specific OTEL/NODE_OPTIONS env vars of the former TypeScript services are replaced by the Java agent's
  * uniform health check `wget -qO - http://localhost:8080/health` (wget is in the misarch-kotlin base image)
  * `JAVA_OPTS: ${MISARCH_JAVA_OPTS:-}` on every Kotlin app (default empty = same JVM defaults as the originals)
  * `JAVA_OPTS_APPEND: ${MISARCH_KEYCLOAK_JAVA_OPTS:-}` on Keycloak (default empty = its built-in -Xmx512m)

Usage: python3 gen_compose.py [path-to-calibration-compose]
"""
import copy
import os
import sys

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
SOURCE = sys.argv[1] if len(sys.argv) > 1 else \
    "/home/devbox/workspace/architecture-optimization/calibration/compose/docker-compose.yaml"

KOTLIN_ORIGINALS = ["address", "catalog", "discount", "notification", "return", "shipment", "tax", "user"]
FORMER_MONGO = ["inventory", "invoice", "order", "payment", "review", "shoppingcart", "wishlist"]
DOMAIN = sorted(KOTLIN_ORIGINALS + FORMER_MONGO + ["media"])
PORTED = DOMAIN + ["simulation"]

POSTGRES_IMAGE = "postgres:16"
RUSTFS_IMAGE = "rustfs/rustfs:1.0.1"

# env vars that only made sense for the Node.js / MongoDB originals
DROP_ENV = {"MONGODB_URI", "DATABASE_URI", "DATABASE_NAME", "NODE_OPTIONS", "OTEL_NODE_RESOURCE_DETECTORS",
            "OTEL_SEMCONV_STABILITY_OPT_IN"}

HEALTHCHECK = {
    "test": "wget -qO - http://localhost:8080/health || exit 1",
    "interval": "60s",
    "timeout": "10s",
    "retries": 20,
    "start_period": "180s",
    "start_interval": "2s",
}


def java_otel_env(name):
    # identical to the original Kotlin services' compose env
    return {
        "OTEL_EXPORTER_OTLP_ENDPOINT": "http://otel-collector:4318",
        "OTEL_METRIC_EXPORT_INTERVAL": "5000",
        "OTEL_LOGS_EXPORTER": "none",
        "OTEL_INSTRUMENTATION_HTTP_CLIENT_EMIT_EXPERIMENTAL_TELEMETRY": "true",
        "OTEL_INSTRUMENTATION_HTTP_SERVER_EMIT_EXPERIMENTAL_TELEMETRY": "true",
        "OTEL_SERVICE_NAME": name,
        "OTEL_TRACES_EXPORTER": "none",
    }


def postgres_env(name):
    return {
        "SPRING_R2DBC_URL": f"r2dbc:postgresql://{name}-db:5432/{name}",
        "SPRING_FLYWAY_URL": f"jdbc:postgresql://{name}-db:5432/{name}",
        "SPRING_R2DBC_USERNAME": "postgres",
        "SPRING_R2DBC_PASSWORD": "postgres",
    }


def postgres_service(name):
    return {
        "image": POSTGRES_IMAGE,
        "restart": "unless-stopped",
        "volumes": [f"{name}-db-data:/var/lib/postgresql/data"],
        "environment": {"POSTGRES_USER": "postgres", "POSTGRES_PASSWORD": "postgres", "POSTGRES_DB": name},
        "healthcheck": {
            "test": f"pg_isready -U postgres -d {name} -h localhost -p 5432",
            "interval": "60s",
            "timeout": "5s",
            "retries": 3,
            "start_period": "180s",
            "start_interval": "2s",
        },
    }


src = yaml.safe_load(open(SOURCE))
services = copy.deepcopy(src["services"])

for name in PORTED:
    app = services[name]
    env = {k: v for k, v in (app.get("environment") or {}).items() if k not in DROP_ENV}
    env.update(java_otel_env(name))
    if name in KOTLIN_ORIGINALS or name in FORMER_MONGO:
        env.update(postgres_env(name))
    if name == "media":
        env["MINIO_ENDPOINT"] = "http://media-minio:9000"
    env["JAVA_OPTS"] = "${MISARCH_JAVA_OPTS:-}"
    app["environment"] = env
    app["image"] = f"misarch-kotlin/{name}:dev"
    app["restart"] = "unless-stopped"
    app["healthcheck"] = dict(HEALTHCHECK)
    if name in KOTLIN_ORIGINALS or name in FORMER_MONGO:
        app["depends_on"] = {f"{name}-db": {"condition": "service_healthy"}}
        services[f"{name}-db"] = postgres_service(name)
    elif name == "media":
        # media creates its bucket once at startup (like the original); RustFS answers 503 until /health/ready is OK
        app["depends_on"] = {"media-minio": {"condition": "service_healthy"}}
    elif name == "simulation":
        app["depends_on"] = ["simulation-rabbitmq"]

services.pop("inventory-mongosetup", None)
# Keycloak 23 runs with its built-in heap (-Xms64m -Xmx512m). Every password grant creates an in-memory user session
# that lives for the realm's SSO idle time (Misarch realm: 3600 s, ~2.4 KB per session), so 512 MB holds ~150k sessions:
# a sustained ~40 logins/s. Above that the old generation fills up and Keycloak spends its CPU in full GCs (logins and
# the gateway's JWKS fetches time out). MISARCH_KEYCLOAK_JAVA_OPTS (e.g. -Xmx2g) is appended after the defaults, and
# the later -Xmx wins.
services["keycloak"].setdefault("environment", {})["JAVA_OPTS_APPEND"] = "${MISARCH_KEYCLOAK_JAVA_OPTS:-}"
# media's S3-compatible blob store: RustFS (MinIO's community server is no longer distributed). The host name stays
# `media-minio` because MINIO_ENDPOINT and the frontend's /api/media proxy use it; credentials admin/password are
# hard-coded in the media service (as in the original).
services["media-minio"] = {
    "image": RUSTFS_IMAGE,
    "restart": "unless-stopped",
    "environment": {"RUSTFS_ACCESS_KEY": "admin", "RUSTFS_SECRET_KEY": "password", "RUSTFS_ADDRESS": "0.0.0.0:9000",
                    "RUSTFS_CONSOLE_ENABLE": "false"},
    "volumes": ["media-rustfs-data:/data"],
    "healthcheck": {"test": ["CMD", "curl", "-fsS", "http://127.0.0.1:9000/health/ready"], "interval": "60s",
                    "timeout": "10s", "retries": 20, "start_period": "60s", "start_interval": "2s"},
}

# keep a stable, readable order: infrastructure first, then per service app/db/dapr/ecs
order = []
for name in list(src["services"].keys()) + ["media-minio"]:
    if name in services and name not in order:
        order.append(name)
for name in services:
    if name not in order:
        order.append(name)
services = {name: services[name] for name in order}

volumes = set()
for spec in services.values():
    for v in spec.get("volumes", []) or []:
        if isinstance(v, str) and not v.startswith(".") and not v.startswith("/") and ":" in v:
            volumes.add(v.split(":")[0])

compose = {"name": "misarch-kotlin", "services": services, "volumes": {v: None for v in sorted(volumes)}}
with open(os.path.join(HERE, "docker-compose.yaml"), "w") as f:
    f.write("# GENERATED by gen_compose.py from the calibration compose of the original -- do not edit by hand\n")
    yaml.safe_dump(compose, f, sort_keys=False, default_flow_style=False, width=200)
print("services:", len(services))
print("kotlin images:", " ".join(f"misarch-kotlin/{n}:dev" for n in PORTED))
