# Requests from the simulation port

## 1. Catalog entry for the RabbitMQ Java client (gradle/libs.versions.toml)

What: add (no version, the Spring Boot BOM manages it: Boot 4.1.1 → `com.rabbitmq:amqp-client` 5.30.0)

```toml
rabbitmq-amqp-client = { module = "com.rabbitmq:amqp-client" }
```

Why: the simulation keeps RabbitMQ (queues `payments-queue`, `shipments-queue`, same broker container as the
original; reasons in `docs/specs/simulation.md` §6). It uses the plain RabbitMQ Java client the same way the original
uses amqplib (one consumer channel, unlimited prefetch, manual ack, reconnect at every full minute). Spring AMQP is not
used, because its listener container has different prefetch, requeue and recovery semantics.

Until the entry exists, `services/simulation/build.gradle.kts` declares `implementation("com.rabbitmq:amqp-client")`.
That uses the same BOM-managed version and no version literal. After the entry is added, replace it with
`implementation(libs.rabbitmq.amqp.client)`.

## 2. (info, no action needed) The simulation does not use the DB, GraphQL or Dapr parts of libs/common

The original simulation has no database, no GraphQL subgraph and no Dapr endpoints (`GET /dapr/subscribe` → 404).
`services/simulation/src/main/resources/application.yaml` therefore excludes `MisarchCommonAutoConfiguration`,
graphql-kotlin's `GraphQLAutoConfiguration`, `DaprAutoConfiguration` and `R2dbcAutoConfiguration`. It also
excludes all actuator web endpoints, because its `/health` answers `{"status":"OK"}` like the NestJS original. If
libs/common later gets an opt-out property for these parts, the simulation could use it instead.

---
## Answers (foundation agent, 2026-10-13 15:00)

1. Done: `gradle/libs.versions.toml` has `rabbitmq-amqp-client = { module = "com.rabbitmq:amqp-client", version.ref = "rabbitmq-amqp-client" }`
   with **5.37.0** (latest stable; Boot's BOM would give 5.30.0 - the plain client is independent of Spring, so the
   newest version is used; recorded in docs/VERSIONS.md). Use `implementation(libs.rabbitmq.amqp.client)`.
2. Fine as is - excluding the auto-configurations in the simulation's `application.yaml` is the intended opt-out
   (no extra properties planned). Note for the compose setup: the simulation health check is
   `wget -qO - http://localhost:8080/health` (any 2xx).

## 3. `curl` in the base image (tools/docker/base.Dockerfile)

What: install `curl` in `misarch-kotlin/jre-base` (e.g. `apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*`).

Why: the original compose files check the simulation's health with `curl -f -X GET http://localhost:8080/health ||
exit 1`, and the calibration compose does too. `eclipse-temurin:25-jre` only has `wget`, so with the original compose
file unchanged the port container would be reported unhealthy. The Kotlin compose setup (wget) is fine, so this only
matters for swapping the image into the original deployment. Spec deviation D8.

**Done (coordinator, 2026-10-13):** `curl` added to `tools/docker/base.Dockerfile`, `BASE_REV` bumped to 3, so the next `tools/image.sh` run rebuilds the base image.
