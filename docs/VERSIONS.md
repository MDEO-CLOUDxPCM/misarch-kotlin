# Versions (researched 2026-10-13)

Single source of truth for build versions: `gradle/libs.versions.toml`. This file records *why* each version was
chosen, when it was released and how compatibility was checked. Sources: Maven Central `maven-metadata.xml` /
POM `Last-Modified` headers (`https://repo1.maven.org/maven2/<group>/<artifact>/`), GitHub release notes,
`services.gradle.org/versions/all`, `api.adoptium.net`. All versions below were verified by building and testing
`libs/common` and `services/address` (unit + Testcontainers integration + SDL tests green) and by running the
`misarch-kotlin/address:dev` image against Postgres 16.

## Stack

| Component | Version | Released | Source | Notes |
|---|---|---|---|---|
| JDK (LTS) | **25** (Temurin 25.0.4.1+1) | 2026-08-21 (25 GA: 2025-09) | api.adoptium.net (`most_recent_lts: 25`; 27 is the latest feature release, not LTS) | Gradle toolchain (auto-provisioned via foojay if no local JDK 25); images `eclipse-temurin:25-jre` |
| Gradle | **9.8.1** | 2026-10-07 | services.gradle.org | Runs on any JDK ≥ 17 (the machine has JDK 21); compiles with the JDK 25 toolchain |
| foojay toolchain resolver | 1.0.0 | 2025-05-19 | plugins.gradle.org | settings plugin |
| Kotlin | **2.4.21** | 2026-10-08 | Maven Central `kotlin-stdlib` (2.5.0-Beta1 exists, not stable) | `jvmTarget = 25`; plugins `jvm`, `plugin.spring` (all-open). No kapt, no KSP |
| kotlinx-coroutines | **1.11.0** | 2026-05-07 | Maven Central | Boot BOM manages 1.10.2; our platform constraint wins |
| Spring Boot | **4.1.1** | 2026-08-20 | Maven Central `spring-boot` (4.2.0-M2 is a milestone) | Spring Framework 7.0.9, Spring Data 2026.0.1 (R2DBC 4.1.1), Reactor 2025.0.7 (core 3.8.7), Netty 4.2.17, Jackson 3.1.5 + Jackson 2.21.5 |
| graphql-kotlin (Expedia) | **11.0.0** | 2026-10-07 | Maven Central, GitHub release v11.0.0 | Built against Spring Boot 4.0.2 / Kotlin 2.3 / Jackson 3; works with Boot 4.1.1 (verified). Defaults to Federation v2.13 → we pin **v2.5** (see PORTING.md) |
| graphql-java | **26.1** | 2026-08-24 | Maven Central | Required by graphql-kotlin 11 (Boot's BOM says 25.0 - unused by Boot itself since we do not use Spring for GraphQL) |
| graphql-java-extended-scalars | **24.0** | 2025-07-06 | Maven Central (latest) | `UUID`, `DateTime`, `Date` scalars. Works with graphql-java 26. `DateTime`'s new `@specifiedBy` is removed to keep the original SDL |
| java-dataloader | 6.0.0 | 2025-11-05 | Maven Central | as required by graphql-kotlin 11 |
| Apollo federation-graphql-java-support | 7.0.0 | - | transitive (graphql-kotlin-federation) | |
| Dapr Java SDK (`dapr-sdk-springboot`, `dapr-sdk`) | **1.18.1** | 2026-07-20 | Maven Central, GitHub release v1.18.1 (1.19.0-rc-2 is a release candidate) | 1.18 officially supports Spring Boot 4 (`io.dapr.spring:dapr-spring-boot-4-starter`). We only use the Spring Boot integration for subscriptions (`@Topic`, `/dapr/subscribe`, `/dapr/config`, `/healthz` - code identical to 1.11 used by the originals). Publish/invoke over the sidecar HTTP API, see below |
| Dapr runtime (sidecar) | 1.15.14 | - | calibration compose | unchanged (sidecars stay as they are) |
| R2DBC PostgreSQL driver | **1.1.3.RELEASE** | 2026-09-21 | Maven Central | Boot BOM manages 1.1.2 |
| r2dbc-pool | 1.0.2.RELEASE | 2024-10-17 | Maven Central (latest) | Boot-managed |
| PostgreSQL JDBC (Flyway only) | **42.7.14** | 2026-10-07 | Maven Central | Boot BOM manages 42.7.13 |
| Flyway (`flyway-core`, `flyway-database-postgresql`) | **13.10.0** | 2026-10-08 | Maven Central | Used directly (not via Boot's Flyway auto-config, which manages 12.4.0), exactly like the originals: JDBC URL from `SPRING_FLYWAY_URL`, credentials from `SPRING_R2DBC_*` |
| Jackson | **3.1.5** (+ 2.21.5) | 2026-07-08 / 2026-07-07 | Boot-managed | Jackson 3 (`tools.jackson.*`) is Boot 4's and graphql-kotlin 11's JSON library; Jackson 2 is on the classpath only because the Dapr SDK uses it. Not upgraded to 3.2.x (latest 3.2.3) because Spring Framework 7.0.x is tested with 3.1.x |
| Testcontainers | **2.0.5** | 2026-04-20 | Maven Central | modules `testcontainers-postgresql`, `testcontainers-junit-jupiter` (2.x artifact names) |
| JUnit | **6.1.3** | 2026-08-07 | Maven Central | Boot BOM manages 6.0.3 |
| MockK | 1.14.11 | 2026-05-29 | Maven Central | |
| AWS SDK for Java v2 (`s3`, `url-connection-client`, BOM) (media) | **2.55.15** | 2026-10-12 | Maven Central `software.amazon.awssdk:s3` (`<release>`) | vendor-neutral S3 client of the media port (replaces the MinIO Java client 9.0.3): path-style, custom endpoint (`MINIO_ENDPOINT`), JDK `URLConnection` HTTP client (Netty/Apache clients excluded), blocking → `Dispatchers.IO`; checksums only when required, no retries (like rust-s3) |
| RustFS (S3 server for media; deploy/compose + media tests) | **1.0.1** | 2026-10-03 | GitHub releases `rustfs/rustfs` (first stable line: 1.0.0 on 2026-09-16), image `rustfs/rustfs:1.0.1` | replaces MinIO (`minio/minio` no longer pullable: Docker Hub repository removed, quay.io 401). Media tests also run against MinIO `bitnamilegacy/minio:2025.7.23` (the original's server) |
| Spring AMQP via `spring-boot-starter-amqp` (simulation) | Boot BOM | - | Maven Central | version (and the RabbitMQ Java client) managed by the Spring Boot BOM |
| OpenTelemetry Java agent | **2.32.0** | 2026-10-03 | Maven Central | baked into the base image like in the original Kotlin images (switch off with `MISARCH_OTEL_AGENT=false`) |
| Postgres (DB containers) | 16 | - | | same major as the original Kotlin services; tests use `postgres:16-alpine` |

## Replaced / dropped libraries

| Original | Replacement | Why |
|---|---|---|
| QueryDSL 5.0 (`com.querydsl`, via **kapt**) + `infobip-spring-data-r2dbc-querydsl` 9.0.2 | Spring Data R2DBC (`CoroutineCrudRepository`, `@Query`, `R2dbcEntityTemplate`/`DatabaseClient`) + a tiny SQL condition builder in `libs/common` (`Condition`, `BaseConnection`) | `com.querydsl` is abandoned (last release 5.1.0, 2024-01-29). The maintained successors exist (OpenFeign QueryDSL 7.7, infobip 11.0.1 for Boot 4), but they still need a *Java annotation processor* to generate the Q-classes, i.e. **kapt** in Kotlin: kapt is in maintenance mode, slow and memory hungry (stub generation per module - a problem on this shared 15.6 GB machine), and QueryDSL has no KSP processor for its SQL module. The services only use simple predicates, ordering, offset/limit, count and a few inner joins, which `Condition` + `BaseConnection` express directly in SQL. One persistence approach for all 16 services (also the former MongoDB ones), no code generation, Boot-managed libraries |
| Spring Boot 3.2.4, Kotlin 1.9.23, JDK 17, graphql-kotlin 7.0.2, graphql-java 21, extended-scalars 21 | Boot 4.1.1, Kotlin 2.4.21, JDK 25, graphql-kotlin 11.0.0, graphql-java 26.1, extended-scalars 24.0 | upgrade |
| Dapr Java SDK 1.11 `DaprClient` (gRPC to the sidecar) for publishing | `EventPublisher` (common): `POST localhost:3500/v1.0/publish/pubsub/<topic>` with Spring `WebClient` | One mechanism for all 16 services (the Rust/TS originals already publish over HTTP; the TS Dapr client defaults to HTTP), testable with a fake HTTP sidecar, no gRPC channel per service. The CloudEvent Dapr delivers is the same. Payload JSON reproduces the SDK's serializer (see `EventJson`). **Deviation for the 8 originally-Kotlin services: publish gRPC → HTTP** |
| `io.dapr.client.domain.CloudEvent` | `org.misarch.common.event.CloudEvent` | Dapr's class targets Jackson 2; Boot 4 decodes request bodies with Jackson 3 |
| Rust: axum + async-graphql + MongoDB; TS: NestJS + MongoDB | Kotlin + Spring Boot + graphql-kotlin + Postgres (R2DBC + Flyway) | uniform stack (goal of the project); mapping rules in PORTING.md |

## Compatibility checks done

- graphql-kotlin 11.0.0 POMs: `spring-boot-*` 4.0.2, `jackson-module-kotlin` 3.1.1 (`tools.jackson`), graphql-java 26.1,
  Kotlin 2.3.0 → runs on Boot 4.1.1 / Kotlin 2.4.21 (address tests).
- Dapr `dapr-sdk-parent` 1.18.1: `springboot.version` 3.5.16 for the core modules, `springboot4.version` for the
  `dapr-spring-boot-4-*` modules; the `io.dapr.springboot` package (`DaprController`, `DaprBeanPostProcessor`) is
  unchanged since 1.11 and works on Spring Framework 7 (address `/dapr/subscribe` test).
- Boot 4.1.1 BOM imported as Gradle *platform*: explicit catalog versions that are higher win (Kotlin, coroutines,
  graphql-java, r2dbc-postgresql, postgresql, JUnit).
- Boot 4 changes that matter for identical behaviour: health shows `groups` by default → disabled
  (`management.endpoint.health.probes.enabled=false`) so `/health` answers `{"status":"UP"}` as before.
