# misarch-kotlin

All 16 domain services of the [MiSArch](https://misarch.github.io/docs) microservice reference architecture -
plus the `simulation` stub (external payment/shipment provider) - re-implemented in **Kotlin on one uniform,
current stack**. In the original, 8 services are Kotlin/Spring Boot, 6 Rust (axum, async-graphql, MongoDB) and
2 TypeScript (NestJS, MongoDB); those language differences distort performance comparisons of architectural
variants. This repository is the baseline for measuring MiSArch's architecture without that noise, and the code
base from which optimised architectures are built later.

Unchanged (taken as they are): the gateway (GraphQL Mesh / Apollo federation, TypeScript), the Dapr sidecars,
the experiment-config sidecars (C#), Keycloak, Redis pub/sub. Every service keeps its original's external contract:
GraphQL SDL, Dapr app id, topics and event payloads, env vars (Postgres instead of MongoDB for the former Rust and
TypeScript services), port and GraphQL path. All services follow the conventions of the Kotlin originals
([docs/PORTING.md](docs/PORTING.md) §2-§6); the visible differences to each original are listed in its
[docs/specs](docs/specs) file. The behaviour-identical port, including the original defects, is the tag `faithful`;
`main` fixes those defects (event payloads changed only where a fix needed it, [docs/fixes](docs/fixes)).

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
reference/dapr-subscribe/   `GET /dapr/subscribe` of the running originals
reference/events/           one real CloudEvent per pub/sub topic of the original (payload formats)
tools/build.sh              the only way to run Gradle (measurement lock, RAM check, 2 build slots)
tools/image.sh <service>    docker image misarch-kotlin/<service>:dev (eclipse-temurin:25-jre + OTel agent)
tools/sdl-diff.sh <service> semantic SDL diff against the original
deploy/compose/             compose setup of the Kotlin version (see deploy/README.md)
docs/PORTING.md             conventions (layout, SDL rules, Dapr patterns, persistence, tests)
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

| Service | Original | Status | SDL diff | Tests (all green) |
|---|---|---|---|---|
| address | Kotlin | done ([fixes](docs/fixes/address.md)) | empty (raw SDL identical except graphql-java's built-in `@oneOf` definition) | 12 (10 integration, 2 SDL incl. the `/` endpoint check) |
| catalog | Kotlin | done ([spec](docs/specs/catalog.md), [fixes](docs/fixes/catalog.md)) | empty (live SDL and misarch/schemas) | 26 (19 integration, 1 migration, 5 unit, 1 SDL) |
| discount | Kotlin | done ([spec](docs/specs/discount.md), [fixes](docs/fixes/discount.md)) | empty (live SDL; `--schemas` differs only by the transform-added `@shareable` on `DiscountsForProductVariant`) | 38 (26 integration, 5 concurrency, 1 migration, 5 unit, 1 SDL) |
| notification | Kotlin | done ([spec](docs/specs/notification.md), [fixes](docs/fixes/notification.md)) | empty (live SDL and misarch/schemas) | 16 (10 integration, 5 unit, 1 SDL) |
| return | Kotlin | done ([spec](docs/specs/return.md), [fixes](docs/fixes/return.md)) | empty (live SDL and misarch/schemas) | 18 (10 integration, 7 unit, 1 SDL) |
| shipment | Kotlin | done ([spec](docs/specs/shipment.md), [fixes](docs/fixes/shipment.md)) | empty (`reference/sdl` and `--schemas`) | 19 (14 integration, 1 migration, 3 unit, 1 SDL) |
| tax | Kotlin | done ([spec](docs/specs/tax.md), [fixes](docs/fixes/tax.md)) | empty (`reference/sdl` and `--schemas`) | 13 (12 integration, 1 SDL) |
| user | Kotlin | done ([spec](docs/specs/user.md), [fixes](docs/fixes/user.md)) | empty (live SDL and misarch/schemas) | 15 (12 integration, 2 unit, 1 SDL) |
| inventory | TypeScript | done ([spec](docs/specs/inventory.md), [fixes](docs/fixes/inventory.md)) | empty (live SDL; `--schemas` differs only by the transform-added `@shareable` on `ProductItemConnection`) | 18 (15 integration, 2 migration, 1 SDL) |
| payment | TypeScript | done ([spec](docs/specs/payment.md), [fixes](docs/fixes/payment.md)) | empty (live SDL; `--schemas` differs only by the transform-added `@shareable` on both connections) | 64 (17 integration, 2 migration, 44 unit, 1 SDL) |
| invoice | Rust | done ([spec](docs/specs/invoice.md), [fixes](docs/fixes/invoice.md)) | empty | 11 (9 integration, 1 migration, 1 SDL) |
| media | Rust | done (no DB, S3 store: RustFS 1.0.1 in compose, MinIO-compatible; [spec](docs/specs/media.md), [fixes](docs/fixes/media.md)) | empty | 17 (2×8 integration against RustFS and MinIO, 1 SDL) |
| order | Rust | done ([spec](docs/specs/order.md), [fixes](docs/fixes/order.md)) | empty (live SDL and misarch/schemas) | 29 (18 integration incl. the 4 checkout invocations, 4 statement, 1 migration, 5 unit incl. the golden `order/order/created`, 1 SDL) |
| review | Rust | done ([spec](docs/specs/review.md), [fixes](docs/fixes/review.md)) | empty | 19 (16 integration, 2 migration, 1 SDL) |
| shoppingcart | Rust | done ([spec](docs/specs/shoppingcart.md), [fixes](docs/fixes/shoppingcart.md)) | empty (live SDL; `--schemas` differs only by the transform-added `@shareable` on `ShoppingCart`) | 15 (14 integration incl. order's `createOrder` invocation contract, 1 SDL) |
| wishlist | Rust | done ([spec](docs/specs/wishlist.md), [fixes](docs/fixes/wishlist.md)) | empty (live SDL and misarch/schemas) | 15 (11 integration, 2 concurrency, 1 migration, 1 SDL) |
| simulation | TypeScript | done (keeps RabbitMQ, no DB; [spec](docs/specs/simulation.md), [fixes](docs/fixes/simulation.md)) | n/a (REST, no subgraph) | 52 (42 unit incl. virtual-time timing and controllers, 3 RabbitMQ adapter, 7 app integration with RabbitMQ) |

**Unification (2026-11):** all services follow the layering, naming and persistence of the Kotlin originals
(`@Table` entities, `CoroutineCrudRepository`s, services with the transactions and events, remote calls outside
transactions, one `EventController` with `@Topic` routes, connections over `BaseConnection`/`connectionQuery`, Kotlin
table names and UUID ids via forward migrations); the async-graphql and NestJS emulation layers are gone, payment
awaits its work in the handlers. The Kotlin originals got composite link-table keys, missing indexes and
service-level transactions. GraphQL SDLs are unchanged.

**Integration of the faithful port (2026-10-13, [docs/INTEGRATION.md](docs/INTEGRATION.md)):** all 17 images run together in
`deploy/compose` (81 containers healthy); seeded with the calibration seeder; all 23 loadgen request types without
errors; federation, replication, the buy saga and the simulation provider behave like the original (incl. its
defects). Differential run against the running original: 5 port deviations found and fixed (F1 Jackson-3
primitives broke the saga at discount; F2 QueryDSL paging missing in 5 services; F3 unknown-id texts `… : null`;
F4 order's async-graphql `data: null`/`_entities` semantics; F5 empty `/health` of the Rust ports); all remaining
differences are documented deviations. `tools/build.sh test`: 668 tests green. Indicative performance smoke: ~0.7x the
CPU per session of the original, createOrder ~6x faster (Rust order's implementation), Rust shoppingcart/review
cheaper than their JVM ports.

**Defect fixes (2026-10-13, [docs/fixes](docs/fixes)):** the faithful port with all original defects is the tag
`faithful`; `main` fixes the original defects that change load or break the end-to-end flows (discount usage upsert,
order amounts, invoices, returns, reviews, payment retries and prepayment status, idempotent event handlers,
inventory reservations, ...), with SDL, app ids, topics and routes unchanged. System-level verification on a fresh
stack ([docs/INTEGRATION.md §9](docs/INTEGRATION.md)): the buy saga completes for all three payment methods with
discounts and without the calibration workarounds, amounts include count, discount and shipment fees, one invoice per
order, the return flow and reviews work, no duplicates under injected redelivery, no pending Redis entries, all 23
loadgen request types without errors.
