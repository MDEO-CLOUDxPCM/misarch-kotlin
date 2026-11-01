# Porting guide (for the service port agents)

Goal: every MiSArch service re-implemented in Kotlin on one stack, **behaviour-identical** to the original, so that
the gateway, the Dapr sidecars and the experiment-config sidecars (ECS) cannot tell the difference. The reference
service is `services/address` (read it first - it is small and shows every pattern below). Versions: `docs/VERSIONS.md`.

## 0. Machine rules (read first)

- RAM is 15.6 GB, shared by many agents and a running MiSArch. **Run Gradle only via `tools/build.sh`** (waits while
  `/home/devbox/workspace/architecture-optimization/MEASURING.lock` exists, waits for free RAM, max. 2 concurrent builds).
  Never call `./gradlew` or `gradle` directly, never start a Gradle daemon, never run `tools/build.sh build` for all
  projects - always name your project's tasks: `tools/build.sh :services:<name>:test`.
- Never start the original or the Kotlin MiSArch stack; do not touch the calibration containers
  (`misarch-*`), `/home/devbox/workspace/misarch` (read-only sources), `architecture-optimization`, `mdeo-cloud`,
  `msa-sim-service`.
- Testcontainers starts one small Postgres per test JVM; that is fine. Do not start other long-running containers.
- Only edit your own `services/<name>/` and your spec `docs/specs/<name>.md`. `libs/common`, `build-logic`,
  `gradle/libs.versions.toml` and `tools/` are shared by all services: keep changes there small and backwards
  compatible, and run `tools/build.sh :libs:common:test :services:address:test` (plus the services that use what
  you changed) afterwards. Commit only on your own branch: small logical commits with short messages.

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
    event/model/*DTO.kt             event payloads (in and out), data classes, one per file, flat (nested payload types too)
    graphql/Query.kt, Mutation.kt   authorization + delegation, no logic
    graphql/model/*.kt              one class per SDL object type (relations are functions)
    graphql/model/connection/*      XConnection + XOrderField + XOrder (+ XFilter), one file per connection
    graphql/input/*Input.kt         GraphQL input types
    graphql/dataloader/*            XDataLoader, one per entity loaded by id
    graphql/federation/*            XResolver, one per @key type
    persistence/model/*Entity.kt    @Table entities (+ *Key classes of link tables)
    persistence/repository/*Repository.kt   CoroutineCrudRepository interfaces
    service/*Service.kt             one per aggregate/entity: validation, transactions, event publishing
    client/                         outbound clients of other services / external providers (order, payment, shipment)
    provider/                       the external provider's status callbacks (payment, shipment)
    ecs/                            EcsVariable beans (only shipment)
  src/main/resources/application.yaml   spring.application.name + service-specific properties only
  src/main/resources/db/migration/      Flyway SQL
  src/test/kotlin/org/misarch/<name>/   <Name>SdlTest, <Name>IntegrationTest, <Name>SchemaTest (schema, migrations), unit tests
```

Rules:
- Package `org.misarch.<name>` (for `return`: ``org.misarch.`return` ``, directory `org/misarch/return`).
- Names: classes after the GraphQL type (`Product`, `ProductEntity`, `ProductRepository`, `ProductService`,
  `ProductDataLoader`, `ProductResolver`, `ProductConnection`); `EventController` (never `DaprEventController`).
- Layers (strict):
  - `graphql`: `Query`/`Mutation` check roles (`dfe.authorizedUser.checkIsEmployee()`), call one service method (or a
    data loader / build a connection for reads) and convert with `toDTO()`; they never use repositories. Model classes
    resolve relations: to-one through a data loader, to-many through a connection, anything else through a service
    method. Field-level authorization lives on the field. Beans: `Query`/`Mutation` get theirs (services,
    `R2dbcEntityTemplate`) by constructor injection; model classes are not beans and take them as
    `@GraphQLIgnore @Autowired` function parameters.
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
- KDoc on public classes and functions, short; the KDoc of a bean does not list its injected dependencies
  (`@param`/`@property`), comments do not describe the code as a port. Explicit imports (no `.*`); no `...Internal`
  function names (name what the function does).

## 3. What `libs/common` gives you (`org.misarch.common.*`)

| Area | API |
|---|---|
| auth | `AuthorizedUser(id, roles)` with `isEmployee`, `isAdmin`, `isBuyer`, `hasRole(r)`, `checkIsEmployee()`, `checkIsAdmin()`, `checkIsBuyer()`; `dfe.authorizedUser` (throws `Unauthorized access`), `dfe.authorizedUserOrNull`. The context factory parses the `Authorized-User` header |
| GraphQL | federated schema hooks (federation **v2.5**, scalars `UUID`/`DateTime`(`OffsetDateTime`)/`Date`(`LocalDate`)/`Upload`, more via a `ScalarMapping` bean), `Node`, `@GraphQLDefaultValue("0")` for argument defaults, `OptionalInput.ifPresent {}`, `Upload` + multipart requests (`misarch.graphql.multipart-upload: true`) |
| data loaders | `IdDataLoader<T, Entity>(repository)` (batch `findAllById`, registered under the class simple name; a missing id fails only its key with `NoSuchElementException()`), `BaseIdDataLoader` for custom loading; access: `dfe.load(XDataLoader::class, id)` / `dfe.dataLoader(XDataLoader::class)` |
| federation | `DataLoaderEntityResolver<T>(T::class, XDataLoader::class)` (load by `id`, safe-cast to `T`), `ReferenceEntityResolver<T>(T::class) { T(it) }` (stub from the id); explicit `FederatedTypeSuspendResolver` only for special cases |
| connections | `BaseConnection<T, Entity>` (`nodes`, `totalCount`, `hasNextPage`, offset pagination `first`/`skip`, `PagingValidation.POSITIVE_LIMIT` by default; all three fields `open`), `NullableNodesConnection` (same, `nodes: [T!]`), `BaseOrder` (common `OrderDirection`), `Ordering<F>` (order inputs with their own direction enum / descriptions; `toOrderBy(defaultField, tieBreak)`), `BaseOrderField`, `BaseOrderDirection`, `CommonOrder`/`CommonOrderField` (order by id), `OrderDirection`, `BaseFilter`; `entityTemplate.connectionQuery(XEntity::class, condition, orderBy, first, skip, join)` = the three statements without the GraphQL type (`ConnectionQuery`, `.distinctValues(column, type)` for `SELECT DISTINCT`) |
| persistence | `BaseEntity<T>` (`id`, `toDTO()`), `Condition` (SQL predicates, `inArray` for id lists), `ConditionQueries` (condition queries outside connections, insert with a known id), `findByIdOrThrow` (`NoSuchElementException()`), naming strategy (table = class name, column = property name, unquoted), Flyway runner, `R2dbcTransactionManager` |
| remote calls | `WebClient.Builder` bean (build the client once per component), `withRetries(retries, delay, onRetry) { ... }` (`org.misarch.common.remote`) |
| Dapr | `EventPublisher.publishEvent(topic, payload)`, `PUBSUB_NAME`, `DaprServiceInvoker.graphql(appId, query, variables, authorizedUser, path)` / `.uri(appId, path)`, `CloudEvent<T>` for subscription handlers, `EventJson` (payload serialization) |
| ECS | `EcsVariable` beans → `GET /ecs/defined-variables`, `POST /ecs/variables` (own auto-configuration; endpoints exist only if a variable is declared) |
| tests (`testFixtures`) | `MisarchIntegrationTest` (app on random port + Postgres container + `FakeDaprSidecar`, helpers `graphql(...)`, `deliverEvent(...)`, `authorizedUserHeader(...)`, `buyer(id)`, `errorMessage(response)`), `SdlComparisonTest("<name>")`, `SdlCanonicalizer`, `PostgresTestDatabase`, `MigrationTestDatabase` (fresh DB: migrate to a version, insert rows, migrate the rest, assert), `FakeDaprSidecar` (records publishes, stubs invocations), `SqlStatementLog` (`@Import` it; `SqlStatementLog.record { graphql(...) }` returns the SQL statements) |

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
| hide a Kotlin member | `@GraphQLIgnore`; inject Spring beans into functions of model classes with `@GraphQLIgnore @Autowired service: XService` (`Query`/`Mutation`: constructor) |
| GraphQL endpoint `/` (the former Rust services are routed to `/v1.0/invoke/<app>/method/`) | `graphql.endpoint: /` in `application.yaml`, and `override val graphqlPath = "/"` in your SDL/integration tests |

## 5. Dapr: subscribe, publish, invoke

- **App id, topics and payloads are the external contract and stay.** Reference subscription lists:
  `reference/dapr-subscribe/<name>.json`.
- **Subscribe**: one `EventController`, routes `/subscription/<topic>`, `/dapr/subscribe` generated by the Dapr SDK:
  ```kotlin
  @Controller
  class EventController(private val userService: UserService) {

      /**
       * Handles a user created event
       *
       * @param cloudEvent the cloud event containing the user created
       */
      @Topic(name = AddressEvents.USER_CREATED, pubsubName = PUBSUB_NAME)     // io.dapr.Topic, org.misarch.common.event.PUBSUB_NAME
      @PostMapping("/subscription/${AddressEvents.USER_CREATED}")
      @ResponseStatus(code = HttpStatus.OK)
      suspend fun onUserCreated(
          @RequestBody
          cloudEvent: CloudEvent<UserDTO>
      ) {
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
- **Invoke** another service: `daprServiceInvoker.graphql(appId, query, variables, authorizedUser, path)` from a
  `client/XClient` component (query documents in `resources/queries/<camelCaseName>.graphql`); plain HTTP:
  `daprServiceInvoker.webClient` with `daprServiceInvoker.uri(appId, path)`.
- **ECS variables**: one `EcsVariable` bean per variable; without one, `/ecs/*` is 404.

## 6. Persistence (Postgres for everybody)

Stack: Spring Data R2DBC (`CoroutineCrudRepository`, derived queries, `@Query`), libs/common `BaseConnection` /
`connectionQuery` / `ConditionQueries` for `Condition` queries, Flyway (JDBC) for the schema. No QueryDSL, no kapt.

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
  (`@Query("SELECT 1 FROM pg_advisory_xact_lock(hashtext(:key))") suspend fun lockForTransaction(key: String): Int`,
  key `<kind>:<id>`), aggregates (`MAX`), multi-join lookups.
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
  (`NAME("name", "id")`) so that pages are stable. Paging: `PagingValidation.POSITIVE_LIMIT` (`first: 0` → `Limit must be
  greater than 0.`, negative → `Offset must not be negative.`) unless the service's contract says otherwise
  (`NON_NEGATIVE`). Connection types whose SDL descriptions differ: override the `open` fields with their own
  `@GraphQLDescription`; a schema with nullable `nodes` uses `NullableNodesConnection`. Order inputs extend
  `BaseOrder`; a schema whose order input has other descriptions or its own `OrderDirection` (the service declares
  `enum class OrderDirection(override val sql: String) : BaseOrderDirection`) implements `Ordering<XOrderField>`
  with `override val direction`/`field` (class name `XOrder` everywhere; graphql-kotlin adds the `Input` suffix,
  `@GraphQLSkipInputSuffix` where the schema has none). Ties: `toOrderBy(XOrderField.ID, listOf("id DESC"))`. Only
  a connection over the distinct values of a column declares the three fields itself and delegates them to the same
  statements:
  ```kotlin
  private val query = entityTemplate.connectionQuery(CategoryCharacteristicValueEntity::class, condition, orderBy,
      first, skip).distinctValues("stringValue", String::class)
  suspend fun nodes(): List<String> = query.nodes()     // totalCount(), hasNextPage() alike
  ```
- **Transactions**: `@Transactional` on the **service method** (works on `suspend` functions), never on `Mutation`,
  controllers or repositories. A service method is `@Transactional` when it writes more than one statement, reads
  then writes (read-modify-write, locks), or writes and publishes an event; a single statement (e.g. an idempotent
  replica insert) needs none. Each mutation and each event handler calls exactly one such method, so it is one
  transaction. A separate transaction inside a guarded call (e.g. discount's order validation: roll back, then
  publish `validation-failed`) is a `@Transactional` method of another service bean.
  **Remote calls are never part of a transaction** (a pooled connection would be held while they run): a
  non-transactional service orchestrates them around short `@Transactional` methods of the entity's service -
  order's `CheckoutService` (calls, then `OrderService.createOrder`), shipment's and payment's
  `*RegistrationService` (store the new row, register it at the provider, publish; a failed registration is undone
  by a second short transaction). Redeliveries stay idempotent through the first transaction's check.
- **Migrations**: Flyway SQL under `src/main/resources/db/migration`. Every service starts with one
  `V1_1__create_relations.sql` in one style:
  - `CREATE EXTENSION IF NOT EXISTS "uuid-ossp";` first; upper-case types: `UUID`, `TEXT` for every string,
    `INTEGER`/`BIGINT`, `DOUBLE PRECISION`, `BOOLEAN`, `TIMESTAMPTZ`, `JSON`, `BIGSERIAL` for a database-assigned
    order; nullable columns without `NULL`;
  - `id UUID PRIMARY KEY` (`DEFAULT uuid_generate_v4()` where the database generates it; no extra `UNIQUE`), link
    tables `PRIMARY KEY (a, b)`; table constraints after the columns: `PRIMARY KEY`, `FOREIGN KEY (x) REFERENCES
    T(id)`, `UNIQUE (...)`, `CHECK`; Postgres's default names (`<table>_pkey`, `_fkey`, `_key`, `_check`; an explicit
    `CONSTRAINT` name only for a multi-column `CHECK`); a foreign key between tables that reference each other as
    `ALTER TABLE ... ADD FOREIGN KEY` once both exist;
  - each index right after its table: `CREATE INDEX <camelTable>_<columns> ON T (...)`;
  - `--` comments only for what the SQL cannot say (why a foreign key is missing, what a generated column or a
    trigger is for); triggers only for invariants across rows that the database must hold under concurrency
    (discount's usage counters, catalog's value/category rule), not for rules the service checks.

  **Forward only** from there: never edit, rename or squash an existing migration (measurement databases carry the
  checksums; a squash needs a conversion of those databases, outside this repository). New migrations use the next
  integer version (`V2__<snake_desc>.sql`, `V3__...`) with a comment header giving the reason (index migrations: the
  measured cost; performance-visible changes in their own migration). Data-preserving (`RENAME`, `ALTER TYPE ...
  USING`, `INSERT ... SELECT`); fail loudly on data that does not fit (`SET NOT NULL`, a pre-check for TEXT → UUID).
  Look up generated constraint names in `pg_constraint` (Postgres shortens names longer than 63 characters). Test a
  forward migration with `MigrationTestDatabase` (rows written by the previous schema survive); every service has a
  `<Service>SchemaTest` for its defaults, constraints and triggers on a freshly migrated database.
- **Document data** (the former MongoDB services): one table per collection; embedded single objects → prefixed
  columns (`currentVersionPrice`) or `JSONB` if never filtered and read as a whole; embedded entity arrays → child
  table with a foreign key (+ `position` if the order is observable); id arrays → link table; replicated foreign
  entities → own replica tables. Reads follow the rules above: a resolver reads its own table, to-one via data
  loader, to-many via a connection over the child/link table, no eager aggregate loads, no in-memory pagination.
  Writes: one transaction per document write (parent, then children); a replacement of a child set (DELETE, then
  INSERTs) locks the parent row first (its UPDATE or `SELECT ... FOR UPDATE`), so concurrent replacements serialise
  and the last writer wins, as with the document write. Atomic `find_one_and_update` → `SELECT ...
  FOR UPDATE` (`SKIP LOCKED` for "any free item"). Enums → `TEXT` with the constant name, timestamps →
  `TIMESTAMPTZ`, an index for every column that is filtered or joined on. Test with `SqlStatementLog` that a query
  not selecting a child connection does not read the child table.
- `media` keeps blobs in an S3/MinIO bucket (`libs.minio`, blocking → `withContext(Dispatchers.IO)`); no table.

## 7. GraphQL behaviour details

- Authorization exactly as the original (which roles may do what, own-resource checks). Errors are graphql-java's
  default `Exception while fetching data (/path) : <message>` (you get it by throwing, section 2).
- Pagination arguments, defaults, ordering and filters as in the reference SDL (some services use `first`/`skip`
  plus `orderBy` inputs).
- Data loaders: one `IdDataLoader` per entity type that is loaded by id from more than one place (entity resolvers,
  object fields). Register them as `@Component`; load with `dfe.load(XDataLoader::class, id)`.
- Health: `GET /health` answers `{"status":"UP"}` (actuator, the libs/common default) in every service.
- Root errors: graphql-kotlin's default (a failed root field is omitted from `data`, all errors are listed).
- Missing rows: `findByIdOrThrow(id)` → `NoSuchElementException()` **without message** (the Kotlin originals'
  `Mono.awaitSingle()`): `Exception while fetching data (/x) : null`.
- Event DTOs are decoded with Jackson-2 semantics for primitives (`fail-on-null-for-primitives: false`, a
  libs/common default; Jackson 3 would answer 400 for a missing primitive, e.g. order's `{"cVC":123}` read as `cvc`).

### Federated tracing (`extensions.ftv1`): off everywhere — project decision (coordinator, 2026-10-13)

The 8 Kotlin originals attach an Apollo federated trace (`extensions.ftv1`, per-field timing → protobuf → base64)
to every GraphQL response (graphql-kotlin 7 / federation-jvm 4.1.0 trace when the request has no
`apollo-federation-include-trace` key); the Rust and TypeScript originals never did, and the gateway does not need
it. This repository exists for **fair performance comparisons on one uniform stack**, so no service emits `ftv1`
(the graphql-kotlin 11 default). `misarch.graphql.federated-tracing: true` (libs/common,
`FederatedTracingConfiguration`: trace every request that does not opt out) remains available only to measure the
cost of tracing in an experiment; do not enable it in a service's application.yaml. List it as an accepted deviation in your spec.

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
   - `GET /dapr/subscribe` lists the topics and pubsub name of `reference/dapr-subscribe/<name>.json` (routes
     `/subscription/<topic>`).
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
