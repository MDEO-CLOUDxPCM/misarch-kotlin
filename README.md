# misarch-kotlin

All 16 domain services of the [MiSArch](https://misarch.github.io/docs) microservice reference architecture -
plus the `simulation` stub (external payment/shipment provider) - re-implemented in **Kotlin on one uniform,
current stack**. In the original, 8 services are Kotlin/Spring Boot, 6 Rust (axum, async-graphql, MongoDB) and
2 TypeScript (NestJS, MongoDB); those language differences distort performance comparisons of architectural
variants. This repository is the baseline for measuring MiSArch's architecture without that noise, and the code
base from which optimised architectures are built later.

Unchanged (taken as they are): the gateway (GraphQL Mesh / Apollo federation, TypeScript), the Dapr sidecars,
the experiment-config sidecars (C#), Keycloak, Redis pub/sub. Each port keeps its original's interface: same
GraphQL SDL, Dapr app id, topics, routes, env vars, ports, health endpoint. The behaviour-identical port, including the
original defects, is the tag `faithful`; `main` fixes those defects (event payloads changed only where a fix needed
it, [docs/fixes](docs/fixes)).

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
| address | Kotlin | done (reference port) | empty (raw SDL identical except graphql-java's built-in `@oneOf` definition) | 12 green ([fixes](docs/fixes/address.md)); image verified behind Dapr 1.15 + ECS (subscription via ECS, invoke, publish; CloudEvent equal to the original's) and on a copy of the original's database (Flyway validates, data served) |
| catalog | Kotlin | done | empty (live SDL and misarch/schemas) | 25 green ([fixes](docs/fixes/catalog.md)); image `misarch-kotlin/catalog:dev` smoke-tested against Postgres 16 (Flyway V1_1–V1_5, `/health`, `/dapr/subscribe`, GraphQL, subscription) |
| discount | Kotlin | done | empty (live SDL; `--schemas` differs only by the transform-added `@shareable` on `DiscountsForProductVariant`) | 37 green ([fixes](docs/fixes/discount.md)); image `misarch-kotlin/discount:dev` smoke-tested on a DB migrated by the original's Flyway 9 (history validates, `/health`, `/dapr/subscribe`, GraphQL, subscription) |
| notification | Kotlin | done | empty (live SDL and misarch/schemas) | 16 green ([fixes](docs/fixes/notification.md)); image `misarch-kotlin/notification:dev` built |
| return | Kotlin | done; defects fixed ([fixes](docs/fixes/return.md): PVV topic subscribed, `createReturn` works end to end) | empty (live SDL and misarch/schemas) | 18 green (10 integration, 1 SDL, 7 unit); image `misarch-kotlin/return:dev` smoke-tested against Postgres 16 (Flyway, `/health`, `/dapr/subscribe`, GraphQL) |
| shipment | Kotlin | done | empty (`reference/sdl` and `--schemas`) | 18 green ([fixes](docs/fixes/shipment.md)); differential run against the original jar (same scenario: GraphQL answers, events, provider calls, DB schema, Flyway checksums identical); starts on an original-migrated DB |
| tax | Kotlin | done | empty (`reference/sdl` and `--schemas`) | 13 green ([fixes](docs/fixes/tax.md)); differential run against the original jar identical (except publish URL encoding); starts on an original-migrated DB |
| user | Kotlin | done | empty (live SDL and misarch/schemas) | 15 green (12 integration, 1 SDL, 2 unit); image `misarch-kotlin/user:dev` built |
| inventory | TypeScript | done (image `misarch-kotlin/inventory:dev`) | empty | 181 green ([fixes](docs/fixes/inventory.md)) + `org.misarch.common.nest` unit tests in libs/common |
| payment | TypeScript | done (image `misarch-kotlin/payment:dev`) | empty | 145 green ([fixes](docs/fixes/payment.md)) |
| invoice | Rust | done; defects fixed ([fixes](docs/fixes/invoice.md): exactly one invoice per order, `invoice/invoice/created` published) | empty | 12 green (11 integration, 1 SDL) |
| media | Rust | done (no DB; S3 via AWS SDK v2; RustFS 1.0.1 in compose, MinIO-compatible) | empty | 19 green (2×8 integration vs RustFS and MinIO, 2 unit, 1 SDL) |
| order | Rust | done; defects fixed ([fixes](docs/fixes/order.md): amounts with count, discount and shipment fees, `orderItem`, visibility events, address ownership, compensation subscribed, compare-and-set placement; [spec](docs/specs/order.md)) | empty (live SDL and misarch/schemas) | 39 green (24 integration incl. the 4 checkout invocations, a captured-event replay and the relational loading, 1 SDL, 14 unit incl. golden `order/order/created` from the running original); image `misarch-kotlin/order:dev` smoke-tested against Postgres 16 (Flyway, `/health`, `/dapr/subscribe`, GraphQL at `/`) |
| review | Rust | done | empty | 26 green ([fixes](docs/fixes/review.md)) + 7 `org.misarch.common.rust` unit tests in libs/common |
| shoppingcart | Rust | done; defects fixed ([fixes](docs/fixes/shoppingcart.md): re-adding a variant returns the existing item, all variants validated); async-graphql error/`_entities` semantics reproduced ([spec](docs/specs/shoppingcart.md), [README](services/shoppingcart/README.md)) | empty (live SDL; `--schemas` differs only by the transform-added `@shareable` on `ShoppingCart`) | 20 green (18 integration incl. the order `createOrder` invocation contract, 1 unit, 1 SDL; async-graphql layer `org.misarch.common.rust.asyncgraphql` + its unit tests in libs/common); image `misarch-kotlin/shoppingcart:dev`; differential run against `ghcr.io/misarch/shoppingcart:main` (72 requests): identical except random-id-dependent node order and graphql-java validation texts |
| wishlist | Rust | done (mongodb-cursor-pagination conventions kept: default 25, `_id DESC` tie-break; `skip` off-by-one fixed, [fixes](docs/fixes/wishlist.md); [spec](docs/specs/wishlist.md), [README](services/wishlist/README.md)) | empty (live SDL and misarch/schemas) | 15 green (shared async-graphql layer in libs/common); image `misarch-kotlin/wishlist:dev`; differential run against `ghcr.io/misarch/wishlist:main` (68 requests): identical except random-id-dependent orderings |
| simulation | TypeScript | done (keeps RabbitMQ, no DB; [spec](docs/specs/simulation.md)) | n/a (REST, no subgraph) | 56 green (48 unit incl. virtual-time timing, 3 RabbitMQ adapter, 5 app integration with RabbitMQ); image `misarch-kotlin/simulation:dev` smoke-tested against RabbitMQ (start-up ordering, endpoints, held = unacked, exit 1 on broker loss) |

**Relational port of the former MongoDB services (2026-10-31, [docs/RELATIONAL-PORT.md](docs/RELATIONAL-PORT.md)):**
order, shoppingcart, wishlist, review and payment access Postgres like the Kotlin originals (a resolver reads its own
table, child collections are connections read only by the field that exposes them, references via DataLoaders) instead
of emulating document loads (one join per order with all items and discounts, in-memory paging, id arrays).

**Integration of the faithful port (2026-10-13, [docs/INTEGRATION.md](docs/INTEGRATION.md)):** all 17 images run together in
`deploy/compose` (81 containers healthy); seeded with the calibration seeder; all 23 loadgen request types without
errors; federation, replication, the buy saga and the simulation provider behave like the original (incl. its
defects). Differential run against the running original: 5 port deviations found and fixed (F1 Jackson-3
primitives broke the saga at discount; F2 QueryDSL paging missing in 5 services; F3 unknown-id texts `… : null`;
F4 order's async-graphql `data: null`/`_entities` semantics; F5 empty `/health` of the Rust ports); all remaining
differences are documented deviations. Shared compatibility code deduplicated into `org.misarch.common.rust.asyncgraphql`
and `org.misarch.common.nest`. `tools/build.sh test`: 668 tests green. Indicative performance smoke: ~0.7x the
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
