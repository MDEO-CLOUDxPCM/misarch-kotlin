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

Every service has the same shape, the one of the Kotlin originals (`services/address` is the smallest complete
example, `catalog` and `discount` show connections with joins, link tables and `Condition` queries).

```
services/<name>/
  build.gradle.kts                  plugins { id("misarch.service-conventions") }  (+ extra deps from libs.versions.toml)
  src/main/kotlin/org/misarch/<name>/
    <Name>Application.kt            @SpringBootApplication, nothing else
    event/<Name>Events.kt           object with the topic constants (PUBSUB_NAME comes from libs/common)
    event/EventController.kt        one @Controller, one @Topic handler per topic, body = one service call
    event/model/*DTO.kt             event payloads (in and out), data classes
    graphql/Query.kt, Mutation.kt   authorization + delegation, no logic
    graphql/model/*.kt              one class per SDL object type (relations are functions)
    graphql/model/connection/*      XConnection + XOrderField + XOrder (+ XFilter), one file per connection
    graphql/input/*Input.kt         GraphQL input types
    graphql/dataloader/*            XDataLoader, one per entity loaded by id
    graphql/federation/*            XResolver, one per @key type
    persistence/model/*Entity.kt    @Table entities (+ *Key classes of link tables)
    persistence/repository/*Repository.kt   CoroutineCrudRepository interfaces
    service/*Service.kt             one per aggregate/entity: validation, transactions, event publishing
    ecs/                            EcsVariable beans (only shipment); provider/ (only shipment, payment)
  src/main/resources/application.yaml   spring.application.name + service-specific properties only
  src/main/resources/db/migration/      Flyway SQL
  src/test/kotlin/org/misarch/<name>/   <Name>SdlTest, <Name>IntegrationTest, <Name>MigrationTest, unit tests
```

Rules:
- Package `org.misarch.<name>` (for `return`: ``org.misarch.`return` ``, directory `org/misarch/return`).
- Names: classes after the GraphQL type (`Product`, `ProductEntity`, `ProductRepository`, `ProductService`,
  `ProductDataLoader`, `ProductResolver`, `ProductConnection`); `EventController` (never `DaprEventController`).
- Layers (strict):
  - `graphql`: `Query`/`Mutation` check roles (`dfe.authorizedUser.checkIsEmployee()`), call one service method (or a
    data loader / build a connection for reads) and convert with `toDTO()`. Model classes resolve relations: to-one
    through a data loader, to-many through a connection. Field-level authorization lives on the field.
  - `service`: takes GraphQL inputs / event DTOs (+ `AuthorizedUser` for ownership checks), returns entities. All
    validation (`require(cond) { "msg" }`), all writes, all events. Never takes a `DataFetchingEnvironment`.
  - `persistence`: entities and repository interfaces only; no logic, no mapping code, no `DatabaseClient`.
  - No `db.sql(...)`/`DatabaseClient`/`R2dbcEntityTemplate.insert` outside libs/common and `@Query` (section 6).
- Missing rows: `repository.findByIdOrThrow(id)` (`NoSuchElementException()` without message, the originals'
  `awaitSingle()` text `Exception while fetching data (/x) : null`); invalid input: `require`/`IllegalArgumentException`
  (`Exception while fetching data (/x) : msg`); missing role: `IllegalStateException` from `checkIs*()`.
- Optional update inputs: `OptionalInput<T>` + `input.x.ifPresent { entity.x = it }` (libs/common).
- No base classes for services; plain constructor injection (`private val repository: XRepository, ...`).
- Shared defaults come from `libs/common` (`MisarchDefaultsEnvironmentPostProcessor`): `/health`, federation on,
  `graphql.packages=org.misarch`, Flyway location, Dapr endpoint, Jackson `fail-on-null-for-primitives: false`.
  Do not repeat them in `application.yaml`.
- KDoc on public classes and functions, short; no "@param repository the provided repository" boilerplate, no
  comments that describe the code as a port.

## 3. What `libs/common` gives you (`org.misarch.common.*`)

| Area | API |
|---|---|
| auth | `AuthorizedUser(id, roles)` with `isEmployee`, `isAdmin`, `isBuyer`, `hasRole(r)`, `checkIsEmployee()`, `checkIsAdmin()`, `checkIsBuyer()`; `dfe.authorizedUser` (throws `Unauthorized access`), `dfe.authorizedUserOrNull`. The context factory parses the `Authorized-User` header |
| GraphQL | federated schema hooks (federation **v2.5**, scalars `UUID`/`DateTime`(`OffsetDateTime`)/`Date`(`LocalDate`)/`Upload`, more via a `ScalarMapping` bean), `Node`, `@GraphQLDefaultValue("0")` for argument defaults, `OptionalInput.ifPresent {}`, `Upload` + multipart requests (`misarch.graphql.multipart-upload: true`) |
| data loaders | `IdDataLoader<T, Entity>(repository)` (batch `findAllById`, registered under the class simple name; a missing id fails only its key with `NoSuchElementException()`), `BaseIdDataLoader` for custom loading; access: `dfe.load(XDataLoader::class, id)` / `dfe.dataLoader(XDataLoader::class)` |
| federation | `DataLoaderEntityResolver<T>(T::class, XDataLoader::class)` (load by `id`, safe-cast to `T`), `ReferenceEntityResolver<T>(T::class) { T(it) }` (stub from the id); explicit `FederatedTypeSuspendResolver` only for special cases |
| connections | `BaseConnection<T, Entity>` (`nodes`, `totalCount`, `hasNextPage`, offset pagination `first`/`skip`, `PagingValidation.QUERYDSL` by default; all three fields `open`), `BaseOrder` (+ `tieBreak`), `BaseOrderField`, `CommonOrder`/`CommonOrderField` (order by id), `OrderDirection`, `BaseFilter`; `ConnectionQuery<Row>` = the three statements without the GraphQL type (other field descriptions, `DISTINCT` selections) |
| persistence | `BaseEntity<T>` (`id`, `toDTO()`), `Condition` (SQL predicates, `inArray` for id lists), `ConditionQueries` (condition queries outside connections, insert with a known id), `findByIdOrThrow`, naming strategy (table = class name, column = property name, unquoted), Flyway runner, `R2dbcTransactionManager` |
| Dapr | `EventPublisher.publishEvent(topic, payload)`, `PUBSUB_NAME`, `DaprServiceInvoker.graphql(appId, query, variables, authorizedUser, method)` / `.uri(appId, method)`, `CloudEvent<T>` for subscription handlers, `EventJson` (payload serialization) |
| ECS | `EcsVariable` beans → `GET /ecs/defined-variables`, `POST /ecs/variables` (own auto-configuration; endpoints exist only if a variable is declared) |
| tests (`testFixtures`) | `MisarchIntegrationTest` (app on random port + Postgres container + `FakeDaprSidecar`, helpers `graphql(...)`, `deliverEvent(...)`, `authorizedUserHeader(...)`), `SdlComparisonTest("<name>")`, `SdlCanonicalizer`, `PostgresTestDatabase`, `MigrationTestDatabase` (fresh DB: migrate to a version, insert rows, migrate the rest, assert), `FakeDaprSidecar` (records publishes, stubs invocations), `SqlStatementLog` (`@Import` it; `SqlStatementLog.record { graphql(...) }` returns the SQL statements) |

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
Descriptions with typos or copy-paste texts of the original (`CommonOrder` = "Discount order") are part of the
contract and stay.

graphql-kotlin cheat sheet:

| SDL | Kotlin |
|---|---|
| description `"""..."""` | `@GraphQLDescription("...")` on class / property (`@property:GraphQLDescription` for constructor `val`s) / function / parameter / enum constant |
| `type T @key(fields: "id")` | `@KeyDirective(fields = FieldSet("id"))` + a resolver bean (`DataLoaderEntityResolver`/`ReferenceEntityResolver`, section 3) |
| `@key(fields: "id", resolvable: false)` (reference stub) | `@KeyDirective(fields = FieldSet("id"), resolvable = false)` (no resolver needed) |
| `@shareable`, `@external`, `@requires(fields: "a")`, `@provides`, `@inaccessible` | `@ShareableDirective`, `@ExternalDirective`, `@RequiresDirective(FieldSet("a"))`, `@ProvidesDirective(...)`, `@InaccessibleDirective` |
| `interface X` | abstract class or interface (implementations must be in `org.misarch.*`, they are found by classpath scanning) |
| `union U = A \| B` | sealed interface/`@GraphQLUnion` |
| field `x: Int!` / `x: Int` | `val x: Int` / `val x: Int?`; function fields: `fun x(...)`, `suspend fun`, or `CompletableFuture<T>` (data loaders) |
| field name differs from Kotlin name (keywords, snake_case) | `@GraphQLName("type")` |
| `input FooInput` | class `FooInput`; **graphql-kotlin appends `Input` to input class names that do not end with it** - name input classes like the SDL type, or `@GraphQLName` + `@GraphQLSkipInputSuffix` for input types without the suffix |
| argument default `first: Int = 10` | `@GraphQLDefaultValue("10") first: Int? = 10` (graphql-kotlin itself ignores Kotlin defaults) |
| custom scalar (e.g. `JSON`) | a `@Bean fun jsonScalar() = ScalarMapping(MyType::class, scalar)` |
| hide a Kotlin member | `@GraphQLIgnore`; inject Spring beans into resolver functions with `@GraphQLIgnore @Autowired repo: Repo` |
| GraphQL endpoint `/` (the former Rust services are routed to `/v1.0/invoke/<app>/method/`) | `graphql.endpoint: /` in `application.yaml`, and `override val graphqlPath = "/"` in your SDL/integration tests |

## 5. Dapr: subscribe, publish, invoke

- **App id, topics and payloads are the external contract and stay.** Reference subscription lists:
  `reference/dapr-subscribe/<name>.json`.
- **Subscribe**: one `EventController`, routes `/subscription/<topic>`, `/dapr/subscribe` generated by the Dapr SDK:
  ```kotlin
  @Controller
  class EventController(private val userService: UserService) {
      @Topic(name = AddressEvents.USER_CREATED, pubsubName = PUBSUB_NAME)     // io.dapr.Topic, org.misarch.common.event.PUBSUB_NAME
      @PostMapping("/subscription/${AddressEvents.USER_CREATED}")
      @ResponseStatus(HttpStatus.OK)
      suspend fun onUserCreated(@RequestBody cloudEvent: CloudEvent<UserDTO>) {
          userService.registerUser(cloudEvent.data)
      }
  }
  ```
  Status codes are Dapr's retry contract: 2xx = done, anything else = redelivery. Handlers are **idempotent**
  (replica inserts `ON CONFLICT DO NOTHING`, "already processed" checks under a lock), so a redelivery is harmless,
  and failures propagate (no fire-and-forget, no swallowed exceptions) so that Dapr redelivers. Incoming JSON is
  decoded by Spring's Jackson 3 with the Kotlin module; unknown properties are ignored, a missing primitive gets its
  default.
- **Publish**: `eventPublisher.publishEvent(XEvents.TOPIC, entity.toEventDTO())` in the service, after the DB write,
  inside the transaction: a failing publish throws and rolls the write back.
- **Payload JSON** (`EventJson`): must equal the original's payload (`reference/events/<topic with _>.json`, assert it
  in a test). Defaults: `null` properties omitted, `val isFoo: Boolean` → `"foo"`, `java.time` as ISO-8601 strings.
  Payloads with explicit nulls: `@JsonInclude(JsonInclude.Include.ALWAYS)` on the DTO; other names: `@JsonProperty`
  (only `com.fasterxml.jackson.annotation.*` annotations work). Timestamps that the original truncated to
  milliseconds stay truncated.
- **Invoke** another service: `daprServiceInvoker.graphql(appId, query, variables, authorizedUser, method)`; plain
  HTTP: `daprServiceInvoker.webClient` with `daprServiceInvoker.uri(appId, path)`.
- **ECS variables**: one `EcsVariable` bean per variable; without one, `/ecs/*` is 404.

## 6. Persistence (Postgres for everybody)

Stack: Spring Data R2DBC (`CoroutineCrudRepository`, derived queries, `@Query`), libs/common `BaseConnection` /
`ConnectionQuery` / `ConditionQueries` for `Condition` queries, Flyway (JDBC) for the schema. No QueryDSL, no kapt.

- **Naming**: table = entity class name (`ProductToCategoryEntity`), column = property name (`productId`), both
  unquoted (Postgres folds them to lower case); no `@Table("...")`/`@Column("...")` except for SQL keywords. Index
  names `<table>_<columns>` in camelCase (`addressEntity_userId`).
- **Entities**: `@Table class FooEntity(var a: String, val b: UUID, @Id override val id: UUID?) : BaseEntity<Foo>`
  (plain class, `var` = mutable column, `id` last; `null` + `DEFAULT uuid_generate_v4()` lets the DB generate it),
  with `toDTO()` (GraphQL type) and `toEventDTO()` (event payload). Replicas of foreign entities are own tables with
  only the needed columns and the foreign id as primary key (`UserEntity(id)`). Subtypes: one table + a
  `discriminator` column.
- **Link tables** (pure many-to-many): the pair is the primary key, no surrogate id:
  ```kotlin
  @Table
  class ProductToCategoryEntity(@Id val key: ProductToCategoryKey) : Persistable<ProductToCategoryKey> {
      override fun getId() = key
      override fun isNew() = true          // link rows are only inserted and deleted: save() always inserts
  }
  data class ProductToCategoryKey(val productId: UUID, val categoryId: UUID)
  interface ProductToCategoryRepository : CoroutineCrudRepository<ProductToCategoryEntity, ProductToCategoryKey> {
      suspend fun findByKeyProductId(productId: UUID): List<ProductToCategoryEntity>
  }
  ```
  SQL: `PRIMARY KEY (productId, categoryId)` (both NOT NULL); an index on the second column if it is looked up on
  its own. `deleteById(key)`, `saveAll(...)`, derived finders over `key.<part>`. A table whose id is part of the SDL
  (discount `DiscountUsageEntity`) is an entity, not a link table.
- **Repositories**: interfaces only (`CoroutineCrudRepository<FooEntity, UUID>`), no `@Repository`, no `@Param`
  (parameter names are compiled in). Inherited CRUD + derived finders (`findByShipmentId`,
  `findByUserIdAndDiscountIdIn`, `existsByOrderId`); `@Query` (+ `@Modifying` for writes) only for what derivation
  cannot express: idempotent replica inserts (`INSERT ... ON CONFLICT (id) DO NOTHING`), upserts, row locks
  (`SELECT id FROM X WHERE id = :id FOR UPDATE`), advisory locks
  (`SELECT 1 FROM pg_advisory_xact_lock(hashtext(:key))`), aggregates (`MAX`), multi-join lookups.
- **Condition queries** (anything a derived finder cannot express, outside a connection): build a `Condition` in the
  service and run it with `ConditionQueries`:
  ```kotlin
  conditionQueries.findAll<DiscountEntity>(condition, join = "INNER JOIN X j ON j.discountId = t.id", orderBy = listOf("t.id ASC"))
  conditionQueries.count<OrderEntity>(Condition.eq("userId", userId))
  conditionQueries.insert(ShipmentEntity(..., id = preGeneratedId))     // INSERT although the id is set
  ```
  Main table alias `t`, join alias `j` (further ones short: `c`, `cr`, ...). Id lists are bound as one array
  (`Condition.inArray("t.id", ids)` → `t.id = ANY(?)`), never one parameter per id.
- **Connections**: built in a GraphQL field function, executed lazily (each statement only when its field is selected):
  `nodes` = `SELECT t.* FROM <table> t <join> WHERE ... ORDER BY ... LIMIT first OFFSET skip`, `totalCount` =
  `SELECT COUNT(t.id)`, `hasNextPage` = `SELECT 1 ... LIMIT 1 OFFSET first + skip`.
  ```kotlin
  suspend fun categories(first: Int? = null, skip: Int? = null, orderBy: CategoryOrder? = null,
                         @GraphQLIgnore @Autowired entityTemplate: R2dbcEntityTemplate,
                         dfe: DataFetchingEnvironment): CategoryConnection =
      CategoryConnection(first, skip, Condition.eq("j.productId", id), orderBy, entityTemplate,
          dfe.authorizedUserOrNull, "INNER JOIN ProductToCategoryEntity j ON j.categoryId = t.id")
  ```
  Each connection class: `@GraphQLDescription` + `@ShareableDirective`, extends `BaseConnection<Dto, Entity>`,
  passes `(order ?: XOrder.DEFAULT).toOrderBy(XOrderField.ID)`; filters implement `BaseFilter.toCondition()`;
  role-dependent restrictions override `authorizedUserFilter()`. Order fields end with a unique column
  (`NAME("name", "id")`) so that pages are stable. Paging: `PagingValidation.QUERYDSL` (`first: 0` → `Limit must be
  greater than 0.`, negative → `Offset must not be negative.`) unless the service's contract says otherwise
  (`NON_NEGATIVE`). Connection types whose SDL descriptions differ: override the `open` fields with their own
  `@GraphQLDescription`, or use `ConnectionQuery`.
- **Transactions**: `@Transactional` on the **service method** (works on `suspend` functions), never on `Mutation`,
  controllers or repositories. A service method is `@Transactional` when it writes more than one statement, reads
  then writes (read-modify-write, locks), or writes and publishes an event; a single statement (e.g. an idempotent
  replica insert) needs none. Each mutation and each event handler calls exactly one such method, so it is one
  transaction. A separate transaction inside a guarded call (e.g. discount's order validation: roll back, then
  publish `validation-failed`) is a `@Transactional` method of another service bean.
- **Migrations**: Flyway SQL under `src/main/resources/db/migration`. **Forward only**: never edit, rename or squash
  an existing migration (measurement databases carry the checksums). New migrations use the next integer version
  (`V2__<snake_desc>.sql` after `V1_7`) with a comment header giving the reason (index migrations: the measured
  cost; performance-visible changes in their own migration). Data-preserving (`RENAME`, `ALTER TYPE ... USING`,
  `INSERT ... SELECT`); fail loudly on data that does not fit (`SET NOT NULL`, a pre-check for TEXT → UUID). Look up
  generated constraint names in `pg_constraint` (Postgres shortens names longer than 63 characters). Test them with
  `MigrationTestDatabase` (rows written by the previous schema survive). Note: `id UUID PRIMARY KEY UNIQUE` creates
  one index only (Postgres drops a UNIQUE that equals the primary key).
- **Document data** (the former MongoDB services): one table per collection; embedded single objects → prefixed
  columns (`currentVersionPrice`) or `JSONB` if never filtered and read as a whole; embedded entity arrays → child
  table with a foreign key (+ `position` if the order is observable); id arrays → link table; replicated foreign
  entities → own replica tables. Reads follow the rules above: a resolver reads its own table, to-one via data
  loader, to-many via a connection over the child/link table, no eager aggregate loads, no in-memory pagination.
  Writes: one transaction per document write (parent, then children). Atomic `find_one_and_update` → `SELECT ...
  FOR UPDATE` (`SKIP LOCKED` for "any free item"). Enums → `VARCHAR` with the constant name, timestamps →
  `TIMESTAMPTZ`, an index for every column that is filtered or joined on. Test with `SqlStatementLog` that a query
  not selecting a child connection does not read the child table.
- `media` keeps blobs in an S3/MinIO bucket (`libs.minio`, blocking → `withContext(Dispatchers.IO)`); no table.

## 7. GraphQL behaviour details

- Authorization exactly as the original (which roles may do what, own-resource checks). Error texts: the Kotlin
  originals' errors read `Exception while fetching data (/path) : <message>` (graphql-java default, you get it by
  throwing). Rust/TS originals return the plain message → `throw GraphQLErrorException("<original message>")`.
  Reproduce messages verbatim (copy them from the original code).
- Pagination arguments, defaults, ordering and filters as in the original (Rust services often use `first`/`skip`
  plus `orderBy` inputs; check the reference SDL).
- Data loaders: one `IdDataLoader` per entity type that is loaded by id from more than one place (entity resolvers,
  object fields). Register them as `@Component`; load with `dfe.load(XDataLoader::class, id)`.
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
