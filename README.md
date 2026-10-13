# misarch-kotlin

All 16 domain services of the [MiSArch](https://misarch.github.io/docs) microservice reference architecture -
plus the `simulation` stub (external payment/shipment provider) - re-implemented in **Kotlin on one uniform,
current stack**. In the original, 8 services are Kotlin/Spring Boot, 6 Rust (axum, async-graphql, MongoDB) and
2 TypeScript (NestJS, MongoDB); those language differences distort performance comparisons of architectural
variants. This repository is the baseline for measuring MiSArch's architecture without that noise, and the code
base from which optimised architectures are built later.

Unchanged (taken as they are): the gateway (GraphQL Mesh / Apollo federation, TypeScript), the Dapr sidecars,
the experiment-config sidecars (C#), Keycloak, Redis pub/sub. Each port is behaviour-identical to its original:
same GraphQL SDL, Dapr app id, topics, routes, event payloads, env vars, ports, health endpoint.

## Stack

JDK 25 (LTS) · Kotlin 2.4.21 · Gradle 9.8.1 · Spring Boot 4.1.1 (WebFlux, coroutines) · graphql-kotlin 11.0.0
(Apollo Federation v2.5) · Spring Data R2DBC + r2dbc-postgresql 1.1.3 · Flyway 13.10 · PostgreSQL 16 ·
Dapr Java SDK 1.18.1 (subscriptions) + Dapr HTTP API (publish, invoke) · Testcontainers 2.0.5 · JUnit 6.
Details, dates and the reasons for replacing QueryDSL/kapt: [docs/VERSIONS.md](docs/VERSIONS.md).

## Layout

```
gradle/libs.versions.toml   the single place for versions
build-logic/                convention plugins: misarch.kotlin-conventions, misarch.library-conventions,
                            misarch.service-conventions (bootJar app.jar, sdlTest task)
libs/common/                shared code: federation setup, Authorized-User, connections, data loaders,
                            persistence (naming, Condition, Flyway), Dapr publish/subscribe/invoke, CloudEvent, ECS
                            variables; testFixtures: integration-test base, fake Dapr sidecar, SDL canonicalizer
services/<name>/            one Spring Boot application per service (package org.misarch.<name>)
reference/sdl/              `{ _service { sdl } }` of the running originals (2026-10-13)
reference/schemas/          misarch/schemas (what the gateway composes)
reference/dapr-subscribe/   `GET /dapr/subscribe` of the running originals; reference/health/: their /health answers
reference/events/           one real CloudEvent per pub/sub topic of the original (payload formats)
tools/build.sh              the only way to run Gradle (measurement lock, RAM check, 2 build slots)
tools/image.sh <service>    docker image misarch-kotlin/<service>:dev (eclipse-temurin:25-jre + OTel agent)
tools/sdl-diff.sh <service> semantic SDL diff against the original
deploy/compose/             compose setup of the Kotlin version (see deploy/README.md)
docs/PORTING.md             porting guide (conventions, SDL rules, Dapr patterns, persistence, tests)
docs/specs/<service>.md     behavioural spec + deviations per service
```

## Build and test

```
tools/build.sh :services:address:test        # unit + Testcontainers integration + SDL tests
tools/sdl-diff.sh address                    # empty diff = same SDL as the original
tools/image.sh address                       # misarch-kotlin/address:dev
```

Gradle runs without daemon, in-process Kotlin compilation, small heaps (`gradle.properties`); always name the
project tasks (`:services:<name>:...`), never build everything at once on the shared machine.

## Run

See [deploy/README.md](deploy/README.md): the same topology as the calibration setup of the original (gateway,
Dapr + experiment-config sidecars, Keycloak, Redis, one Postgres per service, simulation), with the
`misarch-kotlin/<service>:dev` images.

## Status

| Service | Original | Port | SDL diff | Tests |
|---|---|---|---|---|
| address | Kotlin | done (reference port) | empty (raw SDL identical except graphql-java's built-in `@oneOf` definition) | 11 green (9 integration, 2 SDL); image verified behind Dapr 1.15 + ECS (subscription via ECS, invoke, publish; CloudEvent equal to the original's) and on a copy of the original's database (Flyway validates, data served) |
| catalog | Kotlin | done | empty (live SDL and misarch/schemas) | 23 green (17 integration, 1 SDL, 5 unit); image `misarch-kotlin/catalog:dev` smoke-tested against Postgres 16 (Flyway V1_1–V1_5, `/health`, `/dapr/subscribe`, GraphQL, subscription) |
| discount | Kotlin | done | empty (live SDL; `--schemas` differs only by the transform-added `@shareable` on `DiscountsForProductVariant`) | 29 green (24 integration incl. 6 saga, 1 SDL, 4 unit); image `misarch-kotlin/discount:dev` smoke-tested on a DB migrated by the original's Flyway 9 (history validates, `/health`, `/dapr/subscribe`, GraphQL, subscription) |
| notification | Kotlin | done | empty (live SDL and misarch/schemas) | 15 green (9 integration, 1 SDL, 5 unit); image `misarch-kotlin/notification:dev` built |
| return | Kotlin | done (faithful: `createReturn` always fails, PVV topic not subscribed) | empty (live SDL and misarch/schemas) | 18 green (10 integration, 1 SDL, 7 unit); image `misarch-kotlin/return:dev` smoke-tested against Postgres 16 (Flyway, `/health`, `/dapr/subscribe`, GraphQL) |
| shipment | Kotlin | done | empty (`reference/sdl` and `--schemas`) | 17 green (13 integration incl. fake provider + ECS retries, 3 unit, 1 SDL); differential run against the original jar (same scenario: GraphQL answers, events, provider calls, DB schema, Flyway checksums identical); starts on an original-migrated DB |
| tax | Kotlin | done | empty (`reference/sdl` and `--schemas`) | 11 green (10 integration, 1 SDL); differential run against the original jar identical (except publish URL encoding); starts on an original-migrated DB |
| user | Kotlin | done | empty (live SDL and misarch/schemas) | 15 green (12 integration, 1 SDL, 2 unit); image `misarch-kotlin/user:dev` built |
| inventory | TypeScript | done (image `misarch-kotlin/inventory:dev`) | empty | 179 green (16 integration, 162 validation corpus, 1 SDL) + `org.misarch.common.nest` unit tests in libs/common |
| payment | TypeScript | done (image `misarch-kotlin/payment:dev`) | empty | 141 green (12 integration, 125 validation corpus, 3 unit, 1 SDL) |
| invoice | Rust | done (no invoice is ever created, as in the original; spec D1-D6) | empty | 11 green (10 integration, 1 SDL) |
| media | Rust | done (no DB; S3 via AWS SDK v2; RustFS 1.0.1 in compose, MinIO-compatible) | empty | 19 green (2×8 integration vs RustFS and MinIO, 2 unit, 1 SDL) |
| order | Rust | done (faithful incl. defects: `orderItem` always fails, `product-variant/updated` always 422, compensation route unsubscribed; [spec](docs/specs/order.md)) | empty (live SDL and misarch/schemas) | 35 green (19 integration incl. the 4 checkout invocations and a captured-event replay, 1 SDL, 15 unit incl. golden `order/order/created` from the running original); image `misarch-kotlin/order:dev` smoke-tested against Postgres 16 (Flyway, `/health`, `/dapr/subscribe`, GraphQL at `/`) |
| review | Rust | done | empty | 23 green (22 integration, 1 SDL) + 7 `org.misarch.common.rust` unit tests in libs/common |
| shoppingcart | Rust | done (faithful incl. quirks: `createShoppingcartItem` always appends, one random variant validated, async-graphql error/`_entities` semantics reproduced; [spec](docs/specs/shoppingcart.md), [README](services/shoppingcart/README.md)) | empty (live SDL; `--schemas` differs only by the transform-added `@shareable` on `ShoppingCart`) | 20 green (17 integration incl. the order `createOrder` invocation contract, 2 unit, 1 SDL; async-graphql layer `org.misarch.common.rust.asyncgraphql` + its unit tests in libs/common); image `misarch-kotlin/shoppingcart:dev`; differential run against `ghcr.io/misarch/shoppingcart:main` (72 requests): identical except random-id-dependent node order and graphql-java validation texts |
| wishlist | Rust | done (faithful incl. mongodb-cursor-pagination quirks: default 25, `_id DESC` tie-break, `first+1` page; [spec](docs/specs/wishlist.md), [README](services/wishlist/README.md)) | empty (live SDL and misarch/schemas) | 14 green (11 integration, 2 unit, 1 SDL; shared async-graphql layer in libs/common); image `misarch-kotlin/wishlist:dev`; differential run against `ghcr.io/misarch/wishlist:main` (68 requests): identical except random-id-dependent orderings |
| simulation | TypeScript | done (keeps RabbitMQ, no DB; [spec](docs/specs/simulation.md)) | n/a (REST, no subgraph) | 56 green (48 unit incl. virtual-time timing, 3 RabbitMQ adapter, 5 app integration with RabbitMQ); image `misarch-kotlin/simulation:dev` smoke-tested against RabbitMQ (start-up ordering, endpoints, held = unacked, exit 1 on broker loss) |

**Integration (2026-10-13, [docs/INTEGRATION.md](docs/INTEGRATION.md)):** all 17 images run together in
`deploy/compose` (81 containers healthy); seeded with the calibration seeder; all 23 loadgen request types without
errors; federation, replication, the buy saga and the simulation provider behave like the original (incl. its
defects). Differential run against the running original: 5 port deviations found and fixed (F1 Jackson-3
primitives broke the saga at discount; F2 QueryDSL paging missing in 5 services; F3 unknown-id texts `… : null`;
F4 order's async-graphql `data: null`/`_entities` semantics; F5 empty `/health` of the Rust ports); all remaining
differences are documented deviations. Shared compatibility code deduplicated into `org.misarch.common.rust.asyncgraphql`
and `org.misarch.common.nest`. `tools/build.sh test`: 668 tests green. Indicative performance smoke: ~0.7x the
CPU per session of the original, createOrder ~6x faster (Rust order's implementation), Rust shoppingcart/review
cheaper than their JVM ports.
