# Porting guide (for the service port agents)

Goal: every MiSArch service re-implemented in Kotlin on one stack, **behaviour-identical** to the original, so that
the gateway, the Dapr sidecars and the experiment-config sidecars (ECS) cannot tell the difference. The reference
port is `services/address` (read it first - it is small and shows every pattern below). Versions: `docs/VERSIONS.md`.

## 0. Machine rules (read first)

- RAM is 15.6 GB, shared by many agents and a running MiSArch. **Run Gradle only via `tools/build.sh`** (waits while
  `/home/devbox/workspace/architecture-optimization/MEASURING.lock` exists, waits for free RAM, max. 2 concurrent builds).
  Never call `./gradlew` or `gradle` directly, never start a Gradle daemon, never run `tools/build.sh build` for all
  projects - always name your project's tasks: `tools/build.sh :services:<name>:test`.
- Never start the original or the Kotlin MiSArch stack; do not touch the calibration containers
  (`misarch-*`), `/home/devbox/workspace/misarch` (read-only sources), `architecture-optimization`, `mdeo-cloud`,
  `msa-sim-service`.
- Testcontainers starts one small Postgres per test JVM; that is fine. Do not start other long-running containers.
- Only edit your own `services/<name>/` and your spec `docs/specs/<name>.md`. Changes to `libs/common`,
  `build-logic`, `gradle/libs.versions.toml`, `tools/` go through the foundation agent (describe what you need
  in `docs/requests/<name>.md`); small, backwards-compatible additions to `libs/common` are OK if you run
  `tools/build.sh :libs:common:test :services:address:test` afterwards. No git commits.

## 1. Commands

| What | Command |
|---|---|
| compile + all tests | `tools/build.sh :services:<name>:test` |
| SDL test only (no DB, ~20 s) | `tools/build.sh :services:<name>:sdlTest` |
| semantic SDL diff (empty = done) | `tools/sdl-diff.sh <name>` (`--schemas` compares with the misarch/schemas file, `--no-descriptions`) |
| boot jar | `tools/build.sh :services:<name>:bootJar` → `services/<name>/build/libs/app.jar` |
| image `misarch-kotlin/<name>:dev` | `tools/image.sh <name>` |
| run locally (needs a DB) | `java -jar services/<name>/build/libs/app.jar` with the env vars of section 9 |

Add `--console=plain -q` to keep the output short. Test reports: `services/<name>/build/reports/tests/test/index.html`,
results: `build/test-results/test/*.xml`.

## 2. Layout and conventions

```
services/<name>/
  build.gradle.kts                  plugins { id("misarch.service-conventions") }  (+ extra deps from libs.versions.toml)
  src/main/kotlin/org/misarch/<name>/
    <Name>Application.kt            @SpringBootApplication (no extra annotations needed)
    event/                          <Name>Events (topic constants), EventController (@Topic handlers), model/ (event DTOs)
    graphql/                        Query.kt, Mutation.kt, model/, input/, dataloader/, federation/ (entity resolvers),
                                    model/connection/ (connections, order fields, filters)
    persistence/model/              entities (@Table classes implementing BaseEntity)
    persistence/repository/         CoroutineCrudRepository interfaces
    service/                        business logic (@Service), publishes events
    ecs/                            EcsVariable beans (only if the original defines ECS variables)
  src/main/resources/application.yaml   spring.application.name + service-specific properties only
  src/main/resources/db/migration/      Flyway SQL (V1__init.sql, ...)
  src/test/kotlin/org/misarch/<name>/   <Name>SdlTest, <Name>IntegrationTest (+ unit tests)
```

- Package `org.misarch.<name>` (for `return`: ``org.misarch.`return` ``, directory `org/misarch/return`).
- Kotlin originals: keep class names, file structure, descriptions and comments; replace the QueryDSL parts
  (section 6). Rust/TS originals: same structure as address, names follow the GraphQL type names.
- One `application.yaml` per service; shared defaults come from `libs/common`
  (`MisarchDefaultsEnvironmentPostProcessor`): `/health`, federation on, `graphql.packages=org.misarch`, Flyway
  location, Dapr endpoint. Do not repeat them.

## 3. What `libs/common` gives you (`org.misarch.common.*`)

| Area | API |
|---|---|
| auth | `AuthorizedUser(id, roles)` with `isEmployee`, `isAdmin`, `checkIsEmployee()`, `checkIsAdmin()`; `dfe.authorizedUser` (throws `Unauthorized access`), `dfe.authorizedUserOrNull`. The context factory parses the `Authorized-User` header automatically |
| GraphQL | federated schema hooks (federation **v2.5**, scalars `UUID`/`DateTime`(`OffsetDateTime`)/`Date`(`LocalDate`), more via a `ScalarMapping` bean), `Node` base class, `dfe.dataLoader<K, V>(name)`, `@GraphQLDefaultValue("0")` for argument defaults, `GraphQLErrorException("msg")` for errors with a plain message |
| connections | `BaseConnection<T, Entity>` (`nodes`, `totalCount`, `hasNextPage`, offset pagination `first`/`skip`), `BaseOrder`, `BaseOrderField`, `OrderDirection`, `BaseFilter`; `ConnectionQuery<Row>` = the three statements of `BaseConnection` without its GraphQL type (for connection types with other field descriptions, §6) |
| data loaders | `IdDataLoader<T, Entity>(repository)` (batch by id, registered under the class simple name), `BaseIdDataLoader` for custom loading |
| persistence | `BaseEntity<T>` (`id`, `toDTO()`), `Condition` (SQL predicates), naming strategy (table = class name, column = property name, unquoted), Flyway runner, `R2dbcTransactionManager` |
| Dapr | `EventPublisher.publishEvent(topic, payload)`, `DaprServiceInvoker.graphql(appId, query, variables, authorizedUser, method)` / `.uri(appId, method)`, `CloudEvent<T>` for subscription handlers, `EventJson` (payload serialization) |
| ECS | `EcsVariable` beans → `GET /ecs/defined-variables`, `POST /ecs/variables` (endpoints exist only if you declare a variable) |
| tests (`testFixtures`) | `MisarchIntegrationTest` (app on random port + Postgres container + `FakeDaprSidecar`, helpers `graphql(...)`, `deliverEvent(...)`, `authorizedUserHeader(...)`), `SdlComparisonTest("<name>")`, `SdlCanonicalizer`, `PostgresTestDatabase`, `FakeDaprSidecar` (records publishes, stubs invocations), `SqlStatementLog` (`@Import` it; `SqlStatementLog.record { graphql(...) }` returns the SQL statements sent over R2DBC, to assert which tables an operation reads) |

## 4. Keeping the SDL identical

Done means: `tools/sdl-diff.sh <name>` prints no difference against `reference/sdl/<name>.graphql` (the live
`{ _service { sdl } }` of the running original, fetched 2026-10-13). The comparison is semantic
(`SdlCanonicalizer`): ordering of types/fields/arguments/enum values, whitespace in descriptions, `extend type`,
the schema `@link`, directive definitions, `_Any`/`_Entity`/`_Service`/`FieldSet`/`link__*`/`federation__*` types
and `@specifiedBy`/`@extends`/`@link` are ignored; **everything else counts**: type kinds and names, implemented
interfaces, union members, field and argument names and types (incl. nullability and lists), argument default
values, enum values, descriptions, `@key(fields: ...)` (incl. `resolvable: false`), `@shareable`, `@external`,
`@requires`, `@provides`, `@inaccessible`, `@deprecated`. Also check `--schemas` (the misarch/schemas file the
gateway composes); if both references disagree, the live SDL wins - write the difference into your spec.

graphql-kotlin cheat sheet:

| SDL | Kotlin |
|---|---|
| description `"""..."""` | `@GraphQLDescription("...")` on class / property (`@property:GraphQLDescription` for constructor `val`s) / function / parameter / enum constant |
| `type T @key(fields: "id")` | `@KeyDirective(fields = FieldSet("id"))` + a `FederatedTypePromiseResolver<T>` / `FederatedTypeSuspendResolver<T>` bean with `typeName = "T"` |
| `@key(fields: "id", resolvable: false)` (reference stub) | `@KeyDirective(fields = FieldSet("id"), resolvable = false)` (no resolver needed) |
| `@shareable`, `@external`, `@requires(fields: "a")`, `@provides`, `@inaccessible` | `@ShareableDirective`, `@ExternalDirective`, `@RequiresDirective(FieldSet("a"))`, `@ProvidesDirective(...)`, `@InaccessibleDirective` |
| `interface X` | abstract class or interface (implementations must be in `org.misarch.*`, they are found by classpath scanning) |
| `union U = A \| B` | sealed interface/`@GraphQLUnion` |
| field `x: Int!` / `x: Int` | `val x: Int` / `val x: Int?`; function fields: `fun x(...)`, `suspend fun`, or `CompletableFuture<T>` (data loaders) |
| field name differs from Kotlin name (keywords, snake_case) | `@GraphQLName("type")` |
| `input FooInput` | class `FooInput`; **graphql-kotlin appends `Input` to input class names that do not end with it** - name input classes like the SDL type, or `@GraphQLName` + `@GraphQLSkipInputSuffix` for input types without the suffix (e.g. `input CommonOrder`) |
| argument default `first: Int = 10` | `@GraphQLDefaultValue("10") first: Int? = 10` (graphql-kotlin itself ignores Kotlin defaults) |
| custom scalar (e.g. `JSON`) | a `@Bean fun jsonScalar() = ScalarMapping(MyType::class, scalar)` |
| hide a Kotlin member | `@GraphQLIgnore`; inject Spring beans into resolver functions with `@GraphQLIgnore @Autowired repo: Repo` |
| GraphQL endpoint `/` (Rust originals: wishlist, shoppingcart, review, order, media, invoice are routed to `/v1.0/invoke/<app>/method/`) | `graphql.endpoint: /` in `application.yaml`, and `override val graphqlPath = "/"` in your SDL/integration tests |

## 5. Dapr: subscribe, publish, invoke

- **App id, topics, routes, payloads stay exactly as in the original.** Reference subscription lists of the running
  originals: `reference/dapr-subscribe/<name>.json` (route = path your controller must serve).
- **Subscribe** (same as the Kotlin originals; the ECS rewrites the routes, Dapr then POSTs CloudEvents):
  ```kotlin
  @Topic(name = "user/user/created", pubsubName = "pubsub")          // io.dapr.Topic
  @PostMapping("/on-user-creation-event")                            // the original route
  @ResponseStatus(HttpStatus.OK)
  suspend fun onUserCreated(@RequestBody event: CloudEvent<UserEventData>) { ... }   // org.misarch.common.event.CloudEvent
  ```
  `GET /dapr/subscribe`, `/dapr/config`, `/healthz` are served by the Dapr Spring Boot integration. Status codes are
  Dapr's retry contract: 2xx = done, other = retry (Redis streams redelivery). Reproduce the original's
  success/failure behaviour (e.g. the address port answers 500 for a duplicate user id because the original did).
  Incoming JSON is decoded by Spring's Jackson 3 with the Kotlin module; unknown properties are ignored.
- **Publish**: `eventPublisher.publishEvent("address/user-address/created", dto)` → `POST
  http://localhost:3500/v1.0/publish/pubsub/<topic>` (HTTP API for all services; the Kotlin originals used gRPC -
  accepted deviation). A failing publish throws, i.e. the GraphQL mutation fails and its transaction rolls back
  (same as the originals). Publish *after* the DB write, at the same point as the original.
- **Payload JSON** (`EventJson`): must equal the original's payload. Defaults reproduce the Dapr Java SDK serializer
  of the Kotlin originals: `null` properties omitted, `val isFoo: Boolean` → `"foo"`, `java.time` as ISO-8601 strings.
  For Rust originals (serde, mostly `#[serde(rename_all = "camelCase")]`, `None` → `null`) annotate the DTO class with
  `@JsonInclude(JsonInclude.Include.ALWAYS)` and use `@get:JsonProperty("isFoo")` / `@JsonProperty("x")` where names
  differ. Only `com.fasterxml.jackson.annotation.*` annotations work (shared by Jackson 2 and 3). Dates: produce the
  exact string format of the original (e.g. the Rust order writes `2026-10-13T12:57:22.897Z`, millisecond
  precision; use a `String` property formatted explicitly if `OffsetDateTime`'s default ISO format differs).
  **Real payloads of the original: `reference/events/<topic with _>.json`** (one CloudEvent per topic, see
  `reference/README.md`). Write a test asserting the published JSON (see `AddressIntegrationTest`).
- **Invoke** another service (only `order` does this, and shipment/payment call the simulation directly over HTTP):
  `daprServiceInvoker.graphql("shoppingcart", query, variables, authorizedUser, method = "")` (`method = ""` for
  services served at `/`). Forward the `Authorized-User` header exactly like the original. For non-GraphQL calls use
  `daprServiceInvoker.webClient` with `daprServiceInvoker.uri(appId, path)`. Keep calls sequential/parallel as in the
  original (the 4 checkout calls of order are sequential).
- **ECS variables**: only shipment defines some (`providerRetries`, `providerRetryDelay`) → one `EcsVariable` bean
  each; everybody else declares none (then `/ecs/*` is 404, as in the originals).

## 6. Persistence (Postgres for everybody)

Stack: Spring Data R2DBC (`CoroutineCrudRepository`, `@Query`), `R2dbcEntityTemplate`/`DatabaseClient` for
connections and complex queries, Flyway (JDBC) for the schema. No QueryDSL, no kapt (docs/VERSIONS.md says why).

- **Entities**: `@Table class FooEntity(..., @Id override val id: UUID?) : BaseEntity<Foo>`. Table name = class simple
  name, column = property name, both unquoted (Postgres folds to lower case) - identical to the original Kotlin
  schemas, so **original Kotlin migrations are copied unchanged** (address: `V1_1__create_relations.sql`,
  `V1_2__add_name.sql`). `id = null` + `DEFAULT uuid_generate_v4()` lets the DB generate ids; null properties are
  omitted from INSERTs (so `BIGSERIAL`/defaults apply). Use `@Column("...")` only if a column name differs.
- **Repositories**: `interface FooRepository : CoroutineCrudRepository<FooEntity, UUID>`; custom SQL with
  `@Query("SELECT * FROM FooEntity WHERE ...")` (+ `@Modifying` for writes). Suspend functions return
  `FooEntity?`/`List<FooEntity>`/`Flow<FooEntity>`.
- **QueryDSL → SQL**: `entity.userId.eq(id)` → `Condition.eq("userId", id)`, `isNull/isNotNull`, `in` →
  `Condition.inList`, `and`/`or`/`not`, anything else `Condition.raw("t.x > ? AND ...", v)`. QueryDSL joins
  (`applyJoin = { it.innerJoin(LinkEntity.ENTITY).on(LinkEntity.ENTITY.productId.eq(ProductEntity.ENTITY.id)) }`) →
  `join = "INNER JOIN LinkEntity j ON j.productId = t.id"` and conditions on `j.` columns. Order fields: enum
  constants carry column names (`ID("id")`). `BaseConnection` generates
  `SELECT t.* FROM <table> t <join> WHERE ... ORDER BY ... LIMIT first OFFSET skip`, `SELECT COUNT(t.id)` and the
  `hasNextPage` probe exactly like the QueryDSL version. **Ports of the Kotlin originals** override
  `pagingValidation = PagingValidation.QUERYDSL` in every connection (QueryDSL rejects `first: 0`/negative values
  with `Limit must be greater than 0.` / `Offset must not be negative.`); Rust/TS ports reproduce their original's
  paging semantics (default `NONE` = the database decides).
- **Transactions**: keep the original's `@Transactional(propagation = Propagation.REQUIRES_NEW)` on `Mutation`
  (works on `suspend` functions). For event handlers use `@Transactional` on the service method if the original
  wrote several rows atomically.
- **Former MongoDB services (Rust, TS)** - map documents to tables, and **access them like the Kotlin originals do**
  (a true relational port, not a document store emulated on Postgres; required since 2026-10-31, survey and changes:
  [RELATIONAL-PORT.md](RELATIONAL-PORT.md)):
  - one table per collection (`<Thing>Entity`, or the collection's name if you prefer readability - be consistent);
    `_id` (UUID) → `id UUID PRIMARY KEY`;
  - embedded single objects (e.g. a name, an address snapshot, `current_version {price, tax_rate_id}`) → flattened
    columns with a prefix (`currentVersionPrice`), or `JSONB` only if never filtered/sorted and read as a whole;
  - embedded arrays of objects that are GraphQL entities with their own id (order items, cart items) → child table
    with a foreign key to the parent and an `index`/`position` column if the order is observable;
  - arrays of ids (`product_variant_ids`, `internal_ids`) → a link table (`WishlistToProductVariantEntity(wishlistId,
    productVariantId)`); a `UUID[]` column only if the ids are never paged, filtered or looked up one by one;
  - replicated foreign entities (`users`, `product_variants`, ...) → their own tables (like address' `UserEntity`);
  - **reads follow the Kotlin originals**: a root/entity resolver reads the row(s) of its own table only; a to-one
    reference is resolved by its field (an `IdDataLoader`/`KotlinDataLoader`, batched per request, when it is loaded
    from more than one place); a child collection or link set exposed as a connection is read by **that field's
    resolver** as a connection over the child/link table (`WHERE <parent fk> = :id`, joins as in `BaseConnection`):
    `nodes` = `SELECT t.* ... ORDER BY ... LIMIT first OFFSET skip`, `totalCount` = `SELECT COUNT(t.id)`,
    `hasNextPage` = `SELECT 1 ... LIMIT 1 OFFSET first + skip`, each statement run only when its field is selected
    (`BaseConnection`, or `ConnectionQuery` when the connection type's descriptions differ from `BaseConnection`'s).
    No eager aggregate loads (no `parent LEFT JOIN children` for a query that may not select the children), no
    in-memory pagination/filtering of child lists, no "populate" of references the query did not select. Data an
    event payload needs is read explicitly for the event (e.g. order's items and discount ids for
    `order/order/created`);
  - the paging *semantics* of the original stay: default page sizes (mongodb-cursor-pagination's 25), `_id`
    tie-breaks as ORDER BY items, argument validation (unsigned `first`/`skip`) in the field resolver before the
    connection is built. The in-memory formula `hasNextPage = total > nodes + skip` and the cursor library's "extra
    row" are both the probe `LIMIT 1 OFFSET first + skip`;
  - writes: a document insert → one transaction with one INSERT per row (parent, then children - like `saveAll`); a
    `$set` of an array → DELETE + INSERT of the link rows in one transaction; `$push`/`$pull` → INSERT/DELETE of one
    link row;
  - Mongo `count_documents`/`skip`/`limit`/sort by `_id` → `BaseConnection`/`ConnectionQuery` (UUID ordering in
    Postgres and Mongo BinData is byte-wise in both);
  - Mongo transactions / atomic `find_one_and_update` → one Postgres transaction; use `SELECT ... FOR UPDATE` (or
    `SKIP LOCKED` if the original picks "any free" items, e.g. inventory reservation) to keep the same concurrency
    semantics; document the mapping in your spec;
  - enums → `VARCHAR` with the enum constant name; timestamps → `TIMESTAMPTZ` (`OffsetDateTime`); money/counts →
    the original numeric type (`BIGINT`/`INTEGER`/`DOUBLE PRECISION`);
  - create indexes for the original's indexes and for every column you filter or join on.
  - Flyway: `src/main/resources/db/migration/V1__init.sql` (`CREATE EXTENSION IF NOT EXISTS "uuid-ossp";` if you use
    `uuid_generate_v4()`); later schema changes are new versioned migrations that also move the existing data.
  - tests: assert with `SqlStatementLog` that a query not selecting a child connection does not read the child table.
- `media` keeps blobs in an S3/MinIO bucket (`libs.minio` = `io.minio:minio`, blocking → `withContext(Dispatchers.IO)`);
  metadata only if the original kept some.

## 7. GraphQL behaviour details

- Authorization exactly as the original (which roles may do what, own-resource checks). Error texts: the Kotlin
  originals' errors read `Exception while fetching data (/path) : <message>` (graphql-java default, you get it by
  throwing). Rust/TS originals return the plain message → `throw GraphQLErrorException("<original message>")`.
  Reproduce messages verbatim (copy them from the original code).
- Pagination arguments, defaults, ordering and filters as in the original (Rust services often use `first`/`skip`
  plus `orderBy` inputs; check the reference SDL).
- Data loaders: one `IdDataLoader` per entity type that is loaded by id from more than one place (entity resolvers,
  object fields). Register them as `@Component`; use `dfe.dataLoader<UUID, T>(XDataLoader::class.simpleName!!)`.
- Health: `GET /health` must answer like the original (verified on the running originals, docs/INTEGRATION.md F5):
  Kotlin originals `{"status":"UP"}` (actuator, the default), TS `{"status":"OK"}` (`NestWebConfiguration`), Rust 200
  with an **empty body** (`RustCompatConfiguration`, `AsyncGraphqlRoutes`, order's routes; switch the actuator's web
  endpoints off with `management.endpoints.web.exposure.exclude: "*"`).
- Root errors: Kotlin originals omit `data` (graphql-kotlin default), Rust originals answer `"data": null` + the first
  error only (async-graphql; `AsyncGraphqlExecutionConfiguration` / `RustCompatConfiguration`), TS `"data": null`.
- Missing rows in Kotlin-original ports: `throw NoSuchElementException()` **without message** (the originals'
  `kotlinx.coroutines.reactor.Mono.awaitSingle()`): `Exception while fetching data (/x) : null`.
- Event DTOs of Kotlin-original ports are decoded with Jackson-2 semantics for primitives
  (`spring.jackson.deserialization.fail-on-null-for-primitives: false` in their application.yaml; Jackson 3 would
  answer 400 for a missing primitive, e.g. order's `{"cVC":123}` read as `cvc`).

### Federated tracing (`extensions.ftv1`): off everywhere — project decision (coordinator, 2026-10-13)

The 8 Kotlin originals attach an Apollo federated trace (`extensions.ftv1`, per-field timing → protobuf → base64)
to every GraphQL response (graphql-kotlin 7 / federation-jvm 4.1.0 trace when the request has no
`apollo-federation-include-trace` key); the Rust and TypeScript originals never did, and the gateway does not need
it. This repository exists for **fair performance comparisons on one uniform stack**, so no service emits `ftv1`
(the graphql-kotlin 11 default). `misarch.graphql.legacy-federated-tracing: true` (libs/common,
`LegacyFederatedTracingConfiguration`) remains available only to reproduce the original Kotlin services' overhead in
an experiment; do not enable it in a service's application.yaml. List it as an accepted deviation in your spec.

## 8. Tests ("done" means all of this)

1. `<Name>SdlTest : SdlComparisonTest("<name>")` passes = `tools/sdl-diff.sh <name>` is empty (override
   `graphqlPath` for `/`).
2. `<Name>IntegrationTest : MisarchIntegrationTest()` (Postgres via Testcontainers, `FakeDaprSidecar`), covering
   - every subscription route: `deliverEvent(route, topic, json)` → expected rows/side effects and status code,
   - every mutation: result + **published events (topic and exact JSON)** via `dapr.awaitPublished(topic)`,
     authorization failures (no header, wrong role, foreign resource), invalid input errors,
   - every query / connection: filter, order, `first`/`skip`, `totalCount`, `hasNextPage`,
   - federation: `_entities` for each `@key` type the service resolves,
   - service invocations (order): stub the callee with `dapr.stubInvocation(appId, path) { StubResponse(body = ...) }`
     and assert the request (`dapr.invocations`), incl. the forwarded `Authorized-User` header,
   - `GET /dapr/subscribe` equals `reference/dapr-subscribe/<name>.json` (topics, routes, pubsub name).
3. Unit tests for non-trivial logic (price calculation, state machines, validation).
4. `tools/build.sh :services:<name>:test` green; `tools/image.sh <name>` builds.
5. `docs/specs/<name>.md` lists the deviations from the original (with reasons) and the Mongo→Postgres mapping.

Tests share one Postgres per JVM: use random UUIDs per test instead of relying on empty tables.

## 9. Runtime contract (compose/k8s)

| Item | Value |
|---|---|
| app port | 8080 (ECS listens on 5000 and forwards to `localhost:8080`; Dapr `--app-port 5000`) |
| Dapr app id | the service name (unchanged) |
| health | `GET /health` |
| DB env vars | `SPRING_R2DBC_URL=r2dbc:postgresql://<name>-db:5432/<name>`, `SPRING_FLYWAY_URL=jdbc:postgresql://<name>-db:5432/<name>`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD` (for former Mongo services these replace the Mongo URI) |
| other env vars | keep the original names (e.g. payment `SIMULATION_URL`, shipment `MISARCH_SHIPMENT_PROVIDER_ENDPOINT`); map them in `application.yaml` (`foo.bar: ${SIMULATION_URL:http://simulation:8080}`) |
| Dapr endpoint | `DAPR_HTTP_PORT`/`DAPR_HTTP_ENDPOINT` (default `http://localhost:3500`) |
| OpenTelemetry | Java agent in the image, configured by the original `OTEL_*` env vars; `MISARCH_OTEL_AGENT=false` disables it |
| JVM options | none by default (like the originals); `JAVA_OPTS` to add some |

## 10. Spec and deviations

Keep `docs/specs/<name>.md` up to date: behaviour summary, GraphQL operations, events in/out with payloads,
sync calls, persistence mapping, and a **Deviations** section (anything not identical, why, and its expected effect
on performance). Known accepted deviations for all services: publish over Dapr HTTP (Kotlin originals: gRPC),
JDK 25 / Spring Boot 4 runtime, Postgres instead of MongoDB for the former Rust/TS services.

## 11. Optional: `org.misarch.common.rust` (helpers for ports of the Rust services)

Added by the review/invoice/media port (additive, opt-in; nothing changes for services that do not import it).
`@Import(RustCompatConfiguration::class)` on the application class + `graphql.endpoint: /` gives:

| Helper | What it reproduces |
|---|---|
| `RustCompatConfiguration` | `POST /` (single JSON or GraphQL multipart request; batch → 400; response shaped like async-graphql: `data` always present, then `extensions`/`errors`), `GET /` GraphiQL page, `UUID` scalar = `RustUuid.SCALAR`, `DateTime` scalar for `java.time.Instant` = `RustDateTime.SCALAR`, lenient `Authorized-User` parsing, `_entities` with async-graphql semantics (`RustEntitiesDataFetcher`: null element for a resolver returning null, any error fails the whole field; throw `RustEntitiesDataFetcher.entityNotFound()` for a missing/unparsable key), `RustPanic` (throw where the original panics/`unwrap()`s → HTTP 500 for the whole request) |
| `RustAuthorization` / `dfe.rustAuthorizeUser(id)` | `authorization.rs` (header parse failures = absent; exact messages) |
| `RustUuid`, `RustDateTime`, `RustMime` | uuid crate parsing + error texts; chrono `to_rfc3339()` (`+00:00`) and serde (`Z`) formats; `mime` 0.3 parsing (subtype/suffix) |
| `AxumJson` | axum `Json<Event<T>>` status codes (415/413/400/422) and strict serde-like accessors (`reqString`, `optString`, `reqUuid`, `reqUnsigned`, `reqDateTime`, `reqObject`, `reqArray`, ...) |
| `RustDaprRoutes` | exact `GET /dapr/subscribe` JSON (functional route, wins over the SDK's `DaprController`) or 404; `{"status":0}` |
| `MongoCursorPagination`, `RustArgs` | `mongodb-cursor-pagination` 0.3.2 (default 25, `_id: -1` tiebreak is the caller's ORDER BY, skip off-by-one); unsigned `Int` argument errors |

Unit tests: `libs/common/src/test/kotlin/org/misarch/common/rust/RustCompatTest.kt`. Users: review, invoice, media.

Services without a database (media): exclude `org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration`
(`spring.autoconfigure.exclude`) and set `spring.flyway.enabled: false`; common's `transactionManager` bean is
conditional on a `ConnectionFactory` bean (`@ConditionalOnBean`, unchanged for all services with a database).

**Shared helpers without a configuration** (plain objects, usable by every Rust port; added by the dedup of
shoppingcart/wishlist/order, 2026-10-13):

| Helper | What it reproduces | Users |
|---|---|---|
| `RustUuid.tryParse(s)` | uuid crate `parse_str` accepted forms, **never throws** (char-length checks) - the former `compat.RustUuid.parseOrNull` / order's `parseRustUuid`. Same accepted set as `RustUuid.parse`/`parseOrNull`, but `parseOrNull` (review/invoice/media) can throw `StringIndexOutOfBoundsException` for a 38-byte braced non-ASCII input (`{ää…}`) - kept unchanged, so use `tryParse` in new code | shoppingcart, wishlist, order |
| `RustUuid.ORDER` | Rust `Uuid`/BinData/Postgres order (unsigned bytes; `java.util.UUID.compareTo` is signed) - the former `compat.RustUuid.ORDER` / order's `UuidBytesComparator` | shoppingcart, wishlist, order |
| `RustDateTime.now()/toRfc3339()/toSerde()` | (unchanged) - order's former `RustTime.now()/graphql()/event()` were identical code | review, invoice, media, order |
| `dfe.rustAuthorizeUser(id)` | (unchanged) - shoppingcart/wishlist's former `RustAuthorizedUserHeader.authorize(dfe, id)` was provably identical (messages, `id == null`) | review, invoice, media, shoppingcart, wishlist |

### 11a. `org.misarch.common.rust.asyncgraphql` (async-graphql layer of shoppingcart and wishlist)

The former `compat/` packages of shoppingcart and wishlist (byte-identical except the package name), moved
unchanged. A **second variant** next to `RustCompatConfiguration`, not merged with it, because the observable
behaviour differs (each service keeps exactly its current behaviour). Opt-in:
`@Import(AsyncGraphqlConfiguration::class)` on the application class + `graphql.endpoint: /` + the service's
verbatim `src/main/resources/dapr-subscribe.json` (stays in the service); actuator endpoints excluded
(`/health` is served by the routes).

| Helper | What it reproduces | Differs from the `RustCompatConfiguration` variant in |
|---|---|---|
| `AsyncGraphqlConfiguration` (imports `AsyncGraphqlRoutes`, `AsyncGraphqlExecutionConfiguration`) | wires everything below | - |
| `AsyncGraphqlExecutionConfiguration` | only the execution semantics: `AsyncGraphqlSemanticsInstrumentation` + Jackson mix-in so that `"data": null` is written; importable alone (**order** uses it with its own scalars/header/routes; a resolver may also *return* a `GraphQLError`) | - |
| `AsyncGraphqlScalars.UUID` / `.DATE_TIME`, `RustOffsetDateTime.now()/format()` | uuid-crate parsing via `RustUuid.tryParse`; `DateTime` for `OffsetDateTime`, chrono `to_rfc3339()` | error texts (`Failed to parse "UUID": <input>`), Kotlin type `OffsetDateTime`, strict `OffsetDateTime.parse` input; `format` uses `%04d` (differs from `RustDateTime` for years outside 0000-9999) |
| `RustAuthorizedUserHeader`, `RustAuthorizedUserGraphQLContextFactory` | serde parsing of `Authorized-User`, failure = no header | duplicate JSON keys accepted (last wins; `RustAuthorization.parseHeader` rejects them), id parsed with `tryParse` |
| `AsyncGraphqlSemanticsInstrumentation`, `AsyncGraphqlEntitiesDataFetcher` | `_entities` sequential and all-or-nothing, resolver `null` → `Entity not found.`, `"__typename" must be an existing string.` (no path); any field error → `data: null` + first error only; `_entities` paths without index; mutation fields after a failed one are not executed | `RustEntitiesDataFetcher` resolves concurrently, `null` → null element, keeps graphql-java error propagation |
| `RustArgumentParseException`, `AsyncGraphqlDataFetcherExceptionHandler` | argument parse errors located at the argument value | (no `RustPanic` handling) |
| `NoBatchGraphQLRequestParser` | batch → 400 | no multipart requests |
| `AsyncGraphqlRoutes` | `GET /` GraphiQL of async-graphql 7 (resource `org/misarch/common/rust/asyncgraphql/graphiql.html`, `text/html; charset=utf-8`), `GET /health` empty 200, `GET /dapr/subscribe` = the service's `dapr-subscribe.json`, `Content-Type: application/graphql-response+json` on `POST /` | different GraphiQL page; graphql-kotlin's own `POST /` route (no response reshaping) |
| `AxumJsonHandler` | axum `Json<Event<T>>` route handling: 415/400/422 with plain-text bodies, `{"status":0}`, handler failure → 500 | `AxumJson` (review/invoice) uses `mime` rules, 413 above 2 MiB, 422 for duplicate keys, no bodies |

Unit tests: `libs/common/src/test/kotlin/org/misarch/common/rust/RustUuidAndTimeTest.kt` (incl. a corpus check that
`tryParse` and `parseOrNull` accept the same ASCII inputs) and `.../rust/asyncgraphql/AsyncGraphqlCompatTest.kt`;
GraphQL/HTTP behaviour: the shoppingcart and wishlist integration tests. Users: shoppingcart, wishlist.
Not shared (stay in order): `order/rust/RustCompat.kt` (`RustTypeNames`, `RustMessages`, `U64`, `RustError`),
`order/auth` (its header parser has no visible-ASCII check), order's scalars (own error texts) and
`order/event/EventRoutes.kt` (own 415/400/422 texts, `text/plain` without charset).

## 12. Optional: `org.misarch.common.nest` (NestJS emulation for the ports of the TypeScript services)

The files the inventory and payment ports had in common (identical copies; `Js.kt` = payment's superset, i.e.
inventory's plus `MongooseCast`). Opt-in: `@Import(NestWebConfiguration::class)` on the application class; the
service declares a `DaprSubscriptions(json)` bean (its verbatim `GET /dapr/subscribe`) and its own extra scalars.
Nothing is component-scanned (`DetachedTasks`/`DefaultProcessTerminator` are `@Bean`s of `NestWebConfiguration`,
same bean names as before; a `@Primary` `ProcessTerminator` replaces the default, as in the integration tests).

| Helper | What it reproduces |
|---|---|
| `NestWebConfiguration` | `GET /health` `{"status":"OK"}`, `GET /dapr/subscribe` from `DaprSubscriptions`, raw `Authorized-User` context (`RawAuthorizedUserContextFactory`), Nest/Apollo error format (`NestDataFetcherExceptionHandler`), `_entities` errors + graphql-js non-null texts (`EntityErrorInstrumentation`), `UUID` scalar (`Uuid`/`UuidScalar`), `"data": null` mix-in, `DetachedTasks` + `DefaultProcessTerminator` (`misarch.exit-on-unhandled-error` / `MISARCH_EXIT_ON_UNHANDLED_ERROR`) |
| `RolesGuard` | the global roles guard on the raw header (`JSON.parse` with V8 messages, `includes` quirks) |
| `ClassValidator` | `class-validator`/`ValidationPipe` rules and messages (`Schema`, `Property`, `Nested`) |
| `NestExceptions` (`HttpException`, `NotFoundException`, `UnauthorizedException`, `ForbiddenException`, `BadRequestException`, `JsError`), `NestGraphQLErrors`, `EntityErrors` | Nest exceptions and their GraphQL formatting (`@nestjs/apollo` 12) |
| `NestRest` | Express/Nest REST semantics for event handlers (`@Body()` parsing, 201, error bodies) |
| `Js`, `MongooseCast` | JavaScript `typeof`/`String(x)`/`parseInt`/number formatting/`toISOString`; Mongoose `castNumber` |

Stay in the services (behaviour differs or service-specific): `nest/Selection.kt` (`queryKeys`: inventory ignores
fragments, payment reproduces the original's fragment quirks/TypeErrors) and payment's `nest/PaymentScalars.kt`
(`JsDate`, `JsFloat`, `JsonObjectValue` scalars + `PaymentScalarConfiguration`). Unit tests:
`libs/common/src/test/kotlin/org/misarch/common/nest/NestCompatTest.kt`. Users: inventory, payment.
