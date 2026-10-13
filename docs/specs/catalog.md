# Port spec: `catalog`

Original: `/home/devbox/workspace/misarch/catalog` (Kotlin 1.9.23, Spring Boot 3.2.4, graphql-kotlin 7.0.2,
graphql-java 21.4, Spring Data R2DBC + QueryDSL SQL 5.0.0 via `infobip-spring-data-r2dbc-querydsl-boot-starter` 9.0.2
(kapt code generation), Flyway 9.22.3, Dapr Java SDK 1.11.0 (`dapr-sdk-springboot`), java-dataloader 3.2.1,
graphql-java-extended-scalars 21.0, JDK 17, OpenTelemetry java agent).
The published image `ghcr.io/misarch/catalog:main` (built 2025-06-07) was checked: its `BOOT-INF/classes` match
the source tree exactly (same classes, same `application.yaml`, same 5 migrations).

The code is the truth. Where this spec says "quirk", the port must reproduce the behaviour; it must not fix it.

Sections: 0 port status, 1 role, 2 runtime contract, 3 schema, 4 resolvers/data loading, 5 authorization, 6 persistence,
7 connection SQL, 8 mutation logic, 9 events, 10 invocations, 11 env/config, 12 quirks, 13 migration work,
14 test plan, 15 open items, 16 deviations.

---

## 0. Port status (2026-10-13)

**Done.** Code is in `services/catalog` (package `org.misarch.catalog`, same class and file structure as the original). Results:
* `tools/build.sh :services:catalog:test` passes 22 tests: 16 integration tests (Testcontainers Postgres 16 + `FakeDaprSidecar`), 1 SDL test, and 5 unit tests (exact event JSON, `duplicates()`).
* `tools/sdl-diff.sh catalog` shows no difference, against `reference/sdl` and also with `--schemas`.
* `tools/image.sh catalog` builds `misarch-kotlin/catalog:dev`. A smoke test ran it against a throwaway Postgres 16 with the original env vars:
  * Flyway applied V1_1–V1_5.
  * `/health` returned `{"status":"UP"}`.
  * `/dapr/subscribe` was byte-identical to the original.
  * GraphQL queries and the tax-rate subscription worked.
  * The process used about 510 MiB including the OTel agent.

Stack mapping (the details are in §13 and the deviations in §16):

| Original | Port |
|---|---|
| QueryDSL Q-paths and `applyJoin` | `Condition` and SQL `INNER JOIN` strings on libs/common `BaseConnection` |
| infobip `PascalCaseNamingStrategy` | libs/common `MisarchNamingStrategy`; the same unquoted identifiers, so the migrations are copied byte-identical |
| `DaprClient` (gRPC) | libs/common `EventPublisher` (Dapr HTTP API); same payload JSON through `EventJson` |
| Dapr `CloudEvent` | `org.misarch.common.event.CloudEvent` |

## 1. Role in the system

* Owns Category, CategoryCharacteristic (categorical | numerical), CategoryCharacteristicValue, Product,
  ProductVariant, ProductVariantVersion, Product→Category links, ProductVariantVersion→Media links.
* Replicates two foreign entities as id-only tables: `TaxRateEntity` ← `tax/tax-rate/created`,
  `MediaEntity` ← `media/media/created`.
* Publishes 6 replication topics. `catalog/product-variant/created` fans out to 5 services (discount, inventory,
  review, shoppingcart, wishlist). `catalog/product-variant-version/created` goes to order (price, tax rate) and
  shipment (weight). `catalog/product/created` goes to discount and review. `catalog/category/created` goes to discount.
  `catalog/product-variant/updated` goes to order. `catalog/product/updated` has no subscriber.
* Called only by the gateway, through Dapr service invocation `POST /v1.0/invoke/catalog/method/graphql`.
  The gateway composes the subgraph from the static file `schemas/catalog.graphql`; it does not introspect.
  Catalog makes **no** outbound service invocation.
* Gatling hot path (buy scenario): `products(filter:{isPubliclyVisible:true}, first:10, orderBy:{direction:ASC, field:ID}, skip:0){hasNextPage nodes{id internalName isPubliclyVisible} totalCount}`
  and `product(id){categories{hasNextPage totalCount} defaultVariant{id isPubliclyVisible averageRating} id internalName isPubliclyVisible variants{hasNextPage totalCount}}`,
  plus `_entities(ProductVariant/ProductVariantVersion/Product)` calls made by the gateway for cart, order and review views.
  Note: a buyer (non-employee) requesting `internalName` gets an error (see §5). Behaviour must be identical.

## 2. Runtime contract (must stay identical)

| Item | Value |
|---|---|
| Dapr app-id | `catalog` (sidecar `--app-port ${EXPERIMENT_CONFIG_SIDECAR_PORT:-8080}`; the ECS sidecar proxies to 8080) |
| HTTP port | 8080 (Spring default, no `server.port` set) |
| GraphQL | `POST /graphql` (also `GET /graphql`, graphql-kotlin default), `GET /sdl`, `GET /graphiql` (graphql-kotlin defaults). Subscriptions: none. |
| Health | `GET /health` (actuator, `management.endpoints.web.base-path: /`, default exposure = health only) → `{"status":"UP"}`; includes the R2DBC health indicator (503 `DOWN` when the DB is unreachable). Compose healthcheck: `wget -qO - http://localhost:8080/health`. |
| Dapr endpoints (from `dapr-sdk-springboot` `DaprController`) | `GET /healthz` (200, empty), `GET /dapr/config` (actor config JSON), `GET /dapr/subscribe`, actor routes `DELETE /actors/{type}/{id}`, `PUT /actors/{type}/{id}/method/{method}`, `.../method/timer/{timer}`, `.../method/remind/{reminder}` (unused). Only `/dapr/subscribe` matters for behaviour; keep `/healthz` and `/dapr/config` if libs/common offers them. |
| Subscription routes | `POST /subscription/tax/tax-rate/created`, `POST /subscription/media/media/created` |
| Pub/sub component | `pubsub` (Redis Streams in deployment, in-memory in the service's own dev compose) |
| Published topics | `catalog/product/created`, `catalog/product-variant/created`, `catalog/product-variant-version/created`, `catalog/category/created`, `catalog/product/updated`, `catalog/product-variant/updated` |
| Consumed topics | `tax/tax-rate/created`, `media/media/created` |
| Request header | `Authorized-User: {"id":"<uuid>","roles":["employee",...]}` (set by the gateway) |
| DB | PostgreSQL 16, database `catalog`, Flyway migrations V1_1..V1_5 (identical SQL files, see §6) |

`GET /dapr/subscribe` (Dapr SDK 1.11 `DefaultObjectSerializer`: plain Jackson, `NON_NULL`; property name is
`pubsubName`):

```json
[{"pubsubName":"pubsub","topic":"tax/tax-rate/created","route":"/subscription/tax/tax-rate/created","metadata":{}},
 {"pubsubName":"pubsub","topic":"media/media/created","route":"/subscription/media/media/created","metadata":{}}]
```

I checked this on 2026-10-13 by running the original jar (no DB, `SPRING_FLYWAY_ENABLED=false`). The output is byte-identical
to the block above: there is no `deadLetterTopic` and the order is tax first, then media.
`GET /healthz` returns 200 with an empty body. `GET /dapr/config` returns `{"entities":[]}`.
`GET /health` without a DB returns 503 with `Content-Type: application/vnd.spring-boot.actuator.v3+json` and body `{"status":"DOWN"}`.
The captured `_service{sdl}` is identical to the foundation's `reference/sdl/catalog.graphql`, and `/dapr/subscribe` is identical to
`reference/dapr-subscribe/catalog.json`. The port is tested against both.

## 3. GraphQL schema summary

The reference is `misarch/schemas/catalog.graphql`, which is the post-processed `/sdl` output of this service.
graphql-kotlin 7.0.2 emits a Federation **v2.5** `@link` (`https://specs.apollo.dev/federation/v2.5`). The
`FederatedSchemaGeneratorHooks` subclass maps `OffsetDateTime → DateTime` (extended-scalars `DateTimeScalar`) and
`UUID → UUID` (`UUIDScalar`), and `didBuildSchema` calls `clearDirectives()` on the schema builder (this removes the
directive *definitions* from the schema object; the `@key`/`@shareable` usages stay). `graphql.packages = org.misarch.catalog`.

* **Interfaces**: `Node{id: UUID!}`. `CategoryCharacteristic implements Node{category, description, id, name}`
  has the implementations `CategoricalCategoryCharacteristic` and `NumericalCategoryCharacteristic`.
  `CategoryCharacteristicValue{characteristic: CategoryCharacteristic!}` has the implementations
  `CategoricalCategoryCharacteristicValue{value: String!}` and `NumericalCategoryCharacteristicValue{value: Float!}`,
  both `@shareable`.
* **Entities** (`@key(fields:"id", resolvable:true)`): `Category`, `CategoricalCategoryCharacteristic`,
  `NumericalCategoryCharacteristic`, `Product`, `ProductVariant`, `ProductVariantVersion`.
  **Stubs** (`@key(fields:"id", resolvable:false)`): `Media`, `TaxRate`. `_Entity` contains all 8 types.
* **Connections** (`@shareable`; each has `hasNextPage: Boolean!`, `nodes: [X!]!`, `totalCount: Int!`): `ProductConnection`,
  `ProductVariantConnection`, `ProductVariantVersionConnection`, `CategoryConnection`,
  `CategoryCharacteristicConnection`, `CategoryCharacteristicValueConnection`, `MediaConnection`,
  `CategoricalCategoryCharacteristicValueConnection`.
* **Query**: `products(first, skip, orderBy: ProductOrderInput, filter: ProductFilterInput): ProductConnection!`,
  `categories(first, skip, orderBy: CategoryOrderInput): CategoryConnection!`, `product(id: UUID!): Product!`,
  `category(id: UUID!): Category!`, `categoryCharacteristic(id: UUID!): CategoryCharacteristic!`, `_entities`
  (and `_service` from federation).
* **Mutation** (all take a single `input` argument and return a non-null type): `createProduct`,
  `createProductVariant`, `createProductVariantVersion`, `createCategory`,
  `createCategoricalCategoryCharacteristic`, `createNumericalCategoryCharacteristic`, `updateProduct`,
  `updateProductVariant`.
* **Fields with arguments**:
  * `Product.variants(first, skip, orderBy: ProductVariantOrderInput, filter: ProductVariantFilterInput)`
  * `Product.categories(first, skip, orderBy: CategoryOrderInput)`
  * `Category.products(first, skip, orderBy: ProductOrderInput, filter: ProductFilterInput)`
  * `Category.characteristics(first, skip, orderBy: CategoryCharacteristicOrderInput)`
  * `CategoricalCategoryCharacteristic.values(first, skip, orderBy: CategoricalCategoryCharacteristicValueOrderInput)`
  * `ProductVariant.versions(first, skip, orderBy: ProductVariantVersionOrderInput)`
  * `ProductVariantVersion.characteristicValues(first, skip, orderBy: CategoryCharacteristicValueOrderInput)`
  * `ProductVariantVersion.medias(first, skip, orderBy: CommonOrderInput)`
* **Plain object fields**:
  * `Product{id, internalName, isPubliclyVisible, defaultVariant}`
  * `ProductVariant{id, isPubliclyVisible, product, currentVersion}`
  * `ProductVariantVersion{id, name, description, version: Int!, retailPrice: Int!, createdAt: DateTime!, canBeReturnedForDays: Int, weight: Float!, productVariant, taxRate}`
  * `Category{id, name, description}`
  * `NumericalCategoryCharacteristic.unit`
* **Order enums**: `ProductOrderField{ID, INTERNAL_NAME}`, `ProductVariantOrderField{ID}`,
  `ProductVariantVersionOrderField{ID, VERSION, CREATED_AT}`, `CategoryOrderField{ID, NAME}`,
  `CategoryCharacteristicOrderField{ID}`, `CategoryCharacteristicValueOrderField{ID}`,
  `CategoricalCategoryCharacteristicValueOrderField{VALUE}`, `CommonOrderField{ID}`, `OrderDirection{ASC, DESC}`.
  Each `*OrderInput` is `{direction: OrderDirection, field: <enum>}` and both fields are nullable.
* **Filters**: `ProductFilterInput{isPubliclyVisible: Boolean}` and `ProductVariantFilterInput{isPubliclyVisible: Boolean}`.
  These fields have **no description**.
* **Inputs**: `CreateProductInput{internalName, isPubliclyVisible, defaultVariant: ProductVariantInput!, categoryIds: [UUID!]!}`,
  `ProductVariantInput{isPubliclyVisible, initialVersion: ProductVariantVersionInput!}`,
  `CreateProductVariantInput{productId, isPubliclyVisible, initialVersion}`,
  `ProductVariantVersionInput{name, description, retailPrice: Int!, canBeReturnedForDays: Int, categoricalCharacteristicValues: [CategoricalCategoryCharacteristicValueInput!]!, numericalCharacteristicValues: [NumericalCategoryCharacteristicValueInput!]!, taxRateId, weight: Float!, mediaIds: [UUID!]!}`,
  `CreateProductVariantVersionInput` (= the previous input plus `productVariantId`),
  `CreateCategoryInput{name, description, categoricalCharacteristics: [CategoricalCategoryCharacteristicInput!]!, numericalCharacteristics: [NumericalCategoryCharacteristicInput!]!}`,
  `CategoricalCategoryCharacteristicInput{name, description}`, `NumericalCategoryCharacteristicInput{name, description, unit}`,
  `Create{Categorical,Numerical}CategoryCharacteristicInput` (= the previous inputs plus `categoryId`),
  `CategoricalCategoryCharacteristicValueInput{value: String!, characteristicId}`,
  `NumericalCategoryCharacteristicValueInput{value: Float!, characteristicId}`,
  `UpdateProductInput{id, isPubliclyVisible?, internalName?, defaultVariantId?}`, `UpdateProductVariantInput{id, isPubliclyVisible?}`.
* **Description quirks that must stay byte-identical**:
  * `CommonOrderInput` is described as "Discount order".
  * `CategoricalCategoryCharacteristicValueOrderField.VALUE` is described as "Order CategoricalCategoryCharacteristicValue by their id".
  * `CreateCategoricalCategoryCharacteristicInput.categoryId` reads "The Category that the CategoricalCategoryCharacteristicI belongs to".
  * The "Create a new value for a characteristic whose values have no further meaning" description is on `createCategoricalCategoryCharacteristic`.
  * The `ProductVariantVersionInput` fields are described with "of the ProductVariant".

## 4. Resolvers and data loading

### 4.1 Data loaders

The names are the class simple names. A new registry is built per request (graphql-kotlin `KotlinDataLoader` beans).

`BaseIdDataLoader` works as follows:
* `DataLoaderFactory.newDataLoader(BatchLoaderWithContext)` with `setBatchLoaderContextProvider { graphQLContext }`.
* It runs in the request `CoroutineScope` taken from the GraphQL context, and falls back to `CoroutineScope(EmptyCoroutineContext)`.
* It calls `repository.findAllById(ids)` (one `SELECT … WHERE Id IN (…)`).
* It then runs `ids.map { toDTO(entities[it]!!) }`. Consequence: **one unknown id fails the whole batch** with a
  `NullPointerException` that has no message (GraphQL message `Exception while fetching data (<path>) : null`).

The default java-dataloader options apply (caching and batching on, unlimited batch size).

| Loader | Entity → DTO |
|---|---|
| `ProductDataLoader` | ProductEntity → Product |
| `ProductVariantDataLoader` | ProductVariantEntity → ProductVariant |
| `ProductVariantVersionDataLoader` | ProductVariantVersionEntity → ProductVariantVersion |
| `CategoryDataLoader` | CategoryEntity → Category |
| `CategoryCharacteristicDataLoader` | CategoryCharacteristicEntity → Categorical/Numerical by discriminator |
| `CategoricalCategoryCharacteristicDataLoader` | same entity, `as CategoricalCategoryCharacteristic` (ClassCastException for numerical ids, which fails the batch) |
| `NumericalCategoryCharacteristicDataLoader` | same entity, `as NumericalCategoryCharacteristic` (ClassCastException for categorical ids) |
| `TaxRateDataLoader` | TaxRateEntity → TaxRate (a DB round trip just to build the id stub) |

There is no Media loader. `Media` nodes are only produced by `MediaConnection`.

Entity→DTO conversions use `!!`:
* `Product.defaultVariantId!!` and `ProductVariant.currentVersion!!`. These columns are always set by the end of the creating transaction.
* `NumericalCategoryCharacteristic.unit!!`, `CategoricalValue.stringValue!!` and `NumericalValue.doubleValue!!`.

### 4.2 Root and field resolvers

* `Query.products` / `Query.categories` build a connection object. Each connection field (`totalCount`, `nodes`,
  `hasNextPage`) is a separate lazy suspend resolver, so **every selected field runs one SQL query** (up to 3 per connection).
* `Query.product(id)` → `ProductDataLoader.load(id).thenApply { if (!isPubliclyVisible) authorizedUser.checkIsEmployee(); it }`.
* `Query.category(id)` → `CategoryDataLoader`. `Query.categoryCharacteristic(id)` → `CategoryCharacteristicDataLoader`.
* `Product.internalName` (function), `Product.defaultVariant` → `ProductVariantDataLoader.load(defaultVariantId, dfe)`;
  `Product.variants` → `ProductVariantConnection`; `Product.categories` → `CategoryConnection` (join).
* `ProductVariant.product` → `ProductDataLoader`; `ProductVariant.currentVersion` → `ProductVariantVersionDataLoader`;
  `ProductVariant.versions` → `ProductVariantVersionConnection`.
* `ProductVariantVersion.productVariant` → `ProductVariantDataLoader`; `.taxRate` → `TaxRateDataLoader`;
  `.characteristicValues` → `CategoryCharacteristicValueConnection`; `.medias` → `MediaConnection` (join).
* `Category.products` → `ProductConnection` (join); `Category.characteristics` → `CategoryCharacteristicConnection`.
* `CategoryCharacteristic.category` → `CategoryDataLoader`; `CategoryCharacteristicValue.characteristic` →
  `CategoryCharacteristicDataLoader`; `CategoricalCategoryCharacteristic.values` → distinct-value connection (§7.2).
* Repositories are injected into field resolvers as `@GraphQLIgnore @Autowired` function parameters (a graphql-kotlin
  Spring `SpringDataFetcher` feature).
* Field resolvers that return `CompletableFuture` pass `dfe` as the java-dataloader `keyContext` (`load(key, dfe)`). This has no functional effect.

### 4.3 Federation resolvers (`FederatedTypePromiseResolver`)

`Product`, `ProductVariant`, `ProductVariantVersion`, `Category`, `CategoricalCategoryCharacteristic` and
`NumericalCategoryCharacteristic` each resolve as follows:
* They read `representation["id"] as String?`.
* A missing id resolves to `null`.
* Otherwise they call `UUID.fromString` and the matching loader.

There are **no visibility checks** in the resolvers (unlike `Query.product`). `Media` and `TaxRate` have no resolver, so graphql-kotlin's
"unresolvable representation" error applies.

### 4.4 GraphQL context

`AuthorizationGraphQLContextFactory` extends `DefaultSpringGraphQLContextFactory`:
* When the `Authorized-User` header is present, it adds `AuthorizedUser::class → objectMapper.readValue(header, AuthorizedUser)`.
  This uses the Spring ObjectMapper with the Kotlin module, and unknown properties are ignored.
* An invalid header throws while the context is created.

`AuthorizedUser(id: UUID, roles: List<String>)` has two derived properties:
* `isEmployee = "employee" in roles || isAdmin`
* `isAdmin = "admin" in roles`

## 5. Authorization (exact)

Error messages:
* "Unauthorized access": `authorizedUser` is used without a header. This comes from `DataFetchingEnvironment.authorizedUser`.
* "Unauthorized access: <id> is not an employee or admin": `checkIsEmployee` fails.
* The admin variant exists but is unused.

graphql-java wraps these messages as `Exception while fetching data (/path) : <msg>`.

| Operation / field | Rule |
|---|---|
| all 8 mutations | `checkIsEmployee()` before any work |
| `Query.products`, `Category.products` | no error; for non-employees (and anonymous users) the query adds `ProductEntity.IsPubliclyVisible = true` |
| `Product.variants` | if the **product** is not public → `checkIsEmployee()`; then for non-employees it adds `ProductVariantEntity.IsPubliclyVisible = true` |
| `Query.product(id)` | if the product is not public → `checkIsEmployee()` (after loading) |
| `Product.internalName` | always `checkIsEmployee()` (public products too; anonymous users get "Unauthorized access") |
| `Product.defaultVariant` | if the product is not public → `checkIsEmployee()` |
| `ProductVariant.currentVersion` | if the variant is not public → `checkIsEmployee()` |
| `ProductVariant.versions` | always `checkIsEmployee()` |
| `ProductVariant.product`, `Product.categories`, all `ProductVariantVersion.*`, `Query.categories/category/categoryCharacteristic`, `Category.characteristics`, `CategoricalCategoryCharacteristic.values`, `_entities` | no checks |

## 6. Persistence

### 6.1 Schema

The migrations are copied byte-identical from the original `src/main/resources/db/migration`:
* `V1_1__create_relations.sql`
* `V1_2__add_tax_rate.sql`
* `V1_3__add_weight.sql`
* `V1_4__change_can_be_returned_for_days_to_int.sql`
* `V1_5__add_media.sql`

Identifiers are **unquoted**, so PostgreSQL folds them to lower case (`categoryentity.categoryid`). All primary keys are UUIDs.
`uuid-ossp` provides `uuid_generate_v4()` defaults, except `TaxRateEntity` and `MediaEntity`, which have no default.

| Table | Columns (post V1_5) | Constraints |
|---|---|---|
| CategoryEntity | id, name varchar(255) NN, description varchar(255) NN | |
| CategoryCharacteristicEntity | id, discriminator varchar NN (`CATEGORICAL`/`NUMERICAL`), name NN, description NN, unit NULL, categoryId → CategoryEntity | |
| ProductEntity | id, internalName NN, isPubliclyVisible bool NN, defaultVariantId NULL → ProductVariantEntity (`fk_product_default_variant`) | |
| ProductVariantEntity | id, isPubliclyVisible NN, productId → ProductEntity, currentVersion NULL → ProductVariantVersionEntity (`fk_product_variant_version`) | |
| ProductVariantVersionEntity | id, name NN, description NN (varchar 255!), version int NN, retailPrice int NN, createdAt timestamptz NN, canBeReturnedForDays **int** NULL (V1_4), productVariantId → PVE, taxRateId → TaxRateEntity (V1_2, `fk_tax_rate_od`), weight double DEFAULT 0 (nullable) (V1_3) | |
| ProductToCategoryEntity | id, productId, categoryId | UNIQUE(productId, categoryId) |
| CategoryCharacteristicValueEntity | id, discriminator NN, stringValue NULL, doubleValue NULL, categoryCharacteristicId, productVariantVersionId | UNIQUE(categoryCharacteristicId, productVariantVersionId); trigger `trg_check_category_value_product_category` (BEFORE INSERT OR UPDATE) raises `Invalid association between productVariantVersionId and categoryId` if the characteristic's category is not linked to the version's product |
| TaxRateEntity | id (no default) | |
| MediaEntity | id (no default) | |
| ProductVariantVersionToMediaEntity | id, productVariantVersionId, mediaId | UNIQUE(productVariantVersionId, mediaId) |

The FKs have no `ON DELETE` clause. Nothing is ever deleted.

### 6.2 Mapping

infobip installs `PascalCaseNamingStrategy`:
* Table = class simple name (`CategoryEntity`).
* Column = property name with the first letter upper-cased (`CategoryId`, `IsPubliclyVisible`).

R2DBC does not force quoting, and QueryDSL `PostgreSQLTemplates.DEFAULT` does not quote. The SQL therefore uses unquoted mixed-case
identifiers, which match the lower-case catalog. **The port must keep the identifiers unquoted or use the exact lower-case names.**
Spring Data's default snake_case strategy would break (`category_id`).

The entity → table mapping is 1:1 with the tables above. Kotlin property `id` is `@Id`. `UUID?` ids are generated by the DB
default on INSERT (Spring Data `save` with a null id = INSERT; with a non-null id = UPDATE of all columns).

### 6.3 Flyway

A custom `Flyway` bean (`initMethod = "migrate"`, conditional on `spring.flyway.enabled`, default true) is configured with:
* `spring.flyway.url` (env `SPRING_FLYWAY_URL`)
* user/password **from `spring.r2dbc.username/password`**
* locations `classpath:db/migration`
* **`baselineOnMigrate(true)`**

`spring.main.allow-bean-definition-overriding: true`. The history table is the default `flyway_schema_history`.
The port must accept a DB that the original migrated (same checksums → no re-run) and must produce the same tables on an empty DB.

### 6.4 Native and derived queries (port them as-is)

* `TaxRateRepository.createTaxRate`: `INSERT INTO TaxRateEntity (id) VALUES (:id)`. `MediaRepository.createMedia`
  uses the same pattern for `MediaEntity`.
* `CategoryCharacteristicValueRepository.upsert`:
  ```sql
  INSERT INTO CategoryCharacteristicValueEntity (categoryCharacteristicId, productVariantVersionId, stringValue, doubleValue, discriminator)
  VALUES (:cc, :pvv, :s, :d, :disc)
  ON CONFLICT (categoryCharacteristicId, productVariantVersionId) DO UPDATE SET stringValue = :s, doubleValue = :d
  ```
  `discriminator` is bound as the enum name.
* `findValidCategoryCharacteristics(pvvId, ids)`: `SELECT DISTINCT categoryCharacteristic.*` over
  PVV ⋈ PV ⋈ Product ⋈ ProductToCategory ⋈ Category ⋈ CategoryCharacteristic `WHERE pvv.id = :pvv AND cc.id IN (:ids)`.
  The list is expanded. The caller never passes an empty list.
* `findMaxVersionByProductVariantId`: `SELECT MAX(p.version) FROM ProductVariantVersionEntity p WHERE p.productVariantId = :id` (returns null when no rows exist).
* `ProductToCategoryRepository.findByProductId(productId)` is a derived query, so it has no ORDER BY.
* The generic calls are `save`, `saveAll` (one INSERT per row), `findById`, `findAllById`, `existsById`.

## 7. Connection SQL (as generated by QueryDSL in the original; reproduced in the port)

### 7.1 `BaseConnection` (all connections except categorical values)

`cond` is the conjunction, in this order, of the parts that are present:
* the parent `predicate`
* `filter.toExpression()`
* `authorizedUserFilter()`

| Field | SQL |
|---|---|
| `totalCount` | `SELECT count(<pk>) FROM <E> [JOIN …] [WHERE cond]` → Int |
| `nodes` | `SELECT <E columns> FROM <E> [JOIN …] [WHERE cond] ORDER BY <order> OFFSET skip?:0 LIMIT first?:Long.MAX_VALUE` |
| `hasNextPage` | `false` if `first == null`; else `SELECT <E columns> … [WHERE cond] OFFSET first+(skip?:0) LIMIT 1` **without ORDER BY** → has row |

The order is `(field ?: defaultField).expressions` in direction `direction ?: ASC`. When `orderBy` is null, the default is
`ID ASC`. Multi-column orders (the tie-breaker id uses the same direction):
* `ProductOrderField.INTERNAL_NAME` = (internalName, id)
* `CategoryOrderField.NAME` = (name, id)
* `ProductVariantVersionOrderField.VERSION` = (version, id)
* `ProductVariantVersionOrderField.CREATED_AT` = (createdAt, id)

`CommonOrder` ignores `field` and always orders by `MediaEntity.id`.

| Connection (usage) | Entity | Predicate / join | Filter | Auth filter |
|---|---|---|---|---|
| `Query.products` | ProductEntity | – | `IsPubliclyVisible = :v` if given | non-employee: `IsPubliclyVisible = true` |
| `Category.products` | ProductEntity | `INNER JOIN ProductToCategoryEntity ON ProductToCategoryEntity.ProductId = ProductEntity.Id`, `ProductToCategoryEntity.CategoryId = :categoryId` | as above | as above |
| `Query.categories` | CategoryEntity | – | – | – |
| `Product.categories` | CategoryEntity | `INNER JOIN ProductToCategoryEntity ON ...CategoryId = CategoryEntity.Id`, `...ProductId = :productId` | – | – |
| `Product.variants` | ProductVariantEntity | `ProductId = :productId` | `IsPubliclyVisible = :v` | non-employee: `IsPubliclyVisible = true` |
| `ProductVariant.versions` | ProductVariantVersionEntity | `ProductVariantId = :id` | – | – |
| `Category.characteristics` | CategoryCharacteristicEntity | `CategoryId = :id` | – | – |
| `ProductVariantVersion.characteristicValues` | CategoryCharacteristicValueEntity | `ProductVariantVersionId = :id` | – | – |
| `ProductVariantVersion.medias` | MediaEntity | `INNER JOIN ProductVariantVersionToMediaEntity ON ...MediaId = MediaEntity.Id`, `...ProductVariantVersionId = :id` | – | – |

### 7.2 `CategoricalCategoryCharacteristicValueConnection` (`CategoricalCategoryCharacteristic.values`)

`W` = `StringValue IS NOT NULL AND CategoryCharacteristicId = :id`.

| Field | SQL |
|---|---|
| `totalCount` | `SELECT count(DISTINCT StringValue) FROM CategoryCharacteristicValueEntity WHERE W` |
| `nodes` | `SELECT DISTINCT StringValue … WHERE W ORDER BY StringValue <dir> OFFSET skip LIMIT first`. Each row maps to `CategoricalCategoryCharacteristicValue(characteristicId, value)` |
| `hasNextPage` | `false` if `first == null`; else `SELECT DISTINCT StringValue … WHERE W OFFSET first+skip LIMIT 1` (no order) |

Negative `first`/`skip` go straight to PostgreSQL, which raises an error that becomes a GraphQL error.

## 8. Mutation logic (exact order; one transaction per mutation)

`Mutation` is annotated `@Transactional(propagation = REQUIRES_NEW)` and uses an `R2dbcTransactionManager`, so each mutation is one
reactive transaction.
* Any exception rolls back all DB writes.
* **Events are published inside the transaction, before commit.** A failed publish fails the mutation and rolls it back.
  Events that were already published stay published.

Reads (queries) run without a transaction.

All mutations first call `dfe.authorizedUser.checkIsEmployee()`.

1. **createCategory(input)**:
   1. INSERT the category.
   2. **Then** check that the characteristic names are unique across the categorical and numerical lists. On failure, throw
      `IllegalArgumentException("Characteristic names must be unique. Duplicates: [a, b]")`. The INSERT is rolled back.
   3. INSERT each categorical characteristic (`unit` NULL), then each numerical characteristic.
   4. Publish `catalog/category/created`.

   There is no event per characteristic.
2. **createCategoricalCategoryCharacteristic / createNumericalCategoryCharacteristic**:
   1. `existsById(categoryId)`, else `"Category with id <id> does not exist."`.
   2. INSERT.

   There is no event. The result is cast to the concrete DTO type.
3. **createProduct(input)**:
   1. INSERT the product (`defaultVariantId` NULL).
   2. For each categoryId in order: `existsById`, else `"Category with id <id> does not exist."`.
   3. `saveAll` the ProductToCategory rows. A duplicate categoryId therefore causes a **UNIQUE violation** (a DB error message).
   4. Run `createProductVariantInternal(defaultVariant, productId)` (see 4).
   5. UPDATE the product with `defaultVariantId`.
   6. Publish `catalog/product/created` with `categoryIds = input.categoryIds.toSet()` (order kept, deduplicated).
   7. Publish `catalog/product-variant/created`.
   8. Publish `catalog/product-variant-version/created` with `mediaIds = input…initialVersion.mediaIds.toSet()`.
4. **createProductVariant(input)**:
   1. `productRepository.existsById`, else `"Product with id <id> does not exist."`.
   2. Run `createProductVariantInternal`:
      1. INSERT the variant (`currentVersion` NULL).
      2. Run `createProductVariantVersionInternal` (see 5).
      3. UPDATE the variant with `currentVersion`.
   3. Publish `product-variant/created`.
   4. Publish `product-variant-version/created`.
5. **createProductVariantVersion(input)**:
   1. `existsById(productVariantId)`, else `"Product variant with id <id> does not exist."`.
   2. Run `createProductVariantVersionInternal`:
      1. `taxRate existsById`, else `"Tax rate with id <id> does not exist"` (**no trailing period**).
      2. For each mediaId in order: `existsById`, else `"Media with id <id> does not exist."`.
      3. `version = MAX(version)+1`, or 1 if there are no rows. There is **no locking and no unique constraint**, so concurrent creates can produce duplicate version numbers.
      4. INSERT with `createdAt = OffsetDateTime.now()` (JVM default zone).
      5. Upsert the characteristic values (see below).
      6. INSERT the media links for `mediaIds.distinct()` (`saveAll`).
   3. `findById(variant)` and set `currentVersion` = the new id.
   4. UPDATE the variant.
   5. Publish `product-variant-version/created` with `mediaIds.toSet()`.

   **No `product-variant/updated` event** is published, although the current version changed.

   Characteristic-value upsert validation runs only if there is at least one id. It checks, in order:
   1. Duplicates over (categorical ids + numerical ids): `"Duplicate characteristic ids: [<uuids>]"`.
   2. `findValidCategoryCharacteristics`; any id not returned: `"Invalid characteristic ids which cannot be used here: [<uuids>]"` (Kotlin `Set.toString`).
   3. For each valid characteristic, its discriminator must match the list it came from: `"Characteristic id <id> is not valid for this type of characteristic."`.

   After validation, it upserts the categorical values (string, NULL), then the numerical values (NULL, double).
6. **updateProduct(input)**:
   1. `findById(id)`. If not found, `awaitSingle` throws `NoSuchElementException` without message (`Mono.awaitSingle`; the error reads `… : null`, docs/INTEGRATION.md F3).
   2. Apply the non-null `isPubliclyVisible` and `internalName`.
   3. If `defaultVariantId` is non-null:
      1. `findById(variant)` (same NoSuchElement error if missing).
      2. If `variant.productId != product.id`, throw `"Variant with id <v> does not belong to product with id <p>."`.
      3. Set it.
   4. UPDATE (always, even with no changes).
   5. Query the categoryIds with `findByProductId` (DB order).
   6. Publish `catalog/product/updated` (always).
7. **updateProductVariant(input)**:
   1. `findById` (NoSuchElement if missing).
   2. Apply `isPubliclyVisible` if non-null.
   3. UPDATE.
   4. Publish `catalog/product-variant/updated` (always).

Errors use the graphql-java default handler: `{"message":"Exception while fetching data (/<field>) : <msg>", "locations":…, "path":[…], "extensions":{"classification":"DataFetchingException"}}`.

## 9. Events

### 9.1 Published

Mechanism: `DaprClient.publishEvent("pubsub", topic, dto).awaitSingleOrNull()`.
* The SDK 1.11 default is **gRPC** to `127.0.0.1:${DAPR_GRPC_PORT:-50001}`.
* The data is serialized by the SDK's `DefaultObjectSerializer` with content type `application/json`.

That serializer is a **plain `ObjectMapper` (no Kotlin module) with `NON_NULL`**. This produces two wire quirks that must be reproduced:

* **Kotlin `isPubliclyVisible: Boolean` serializes as `"publiclyVisible"`.** The bean getter `isPubliclyVisible()` is
  treated by Jackson as an is-getter for property `publiclyVisible`, and the private field is not visible. The docs say
  `isPubliclyVisible`; the wire carries `publiclyVisible`. (Consumers today: inventory (TS) does not use it; order (Rust)
  expects `isPubliclyVisible: String` on `product-variant/updated`, so it fails either way; discount and review ignore it.)
  **Verified** by serializing the original compiled DTO classes with Jackson 2.15.4 configured like `DefaultObjectSerializer`:
  `{"id":…,"internalName":"intern","defaultVariantId":…,"categoryIds":[…],"publiclyVisible":true}`.
* `null` fields are omitted (`canBeReturnedForDays`).
* Key order (verified): declaration order, with `publiclyVisible` moved to the **end**. PVV:
  `id,name,description,version,retailPrice,createdAt,[canBeReturnedForDays],productVariantId,taxRateId,weight,mediaIds`.
  `weight` 1.0 prints as `1.0`.

Dapr wraps the data in a CloudEvent (`type: com.dapr.event.sent`, `source: catalog`, `datacontenttype: application/json`).
An HTTP publish (`POST /v1.0/publish/pubsub/<topic>`, `Content-Type: application/json`, same body) yields the same envelope.

| Topic | Trigger | Data JSON |
|---|---|---|
| `catalog/category/created` | createCategory | `{"id","name","description"}` |
| `catalog/product/created` | createProduct | `{"id","internalName","defaultVariantId","categoryIds":[…],"publiclyVisible":bool}` |
| `catalog/product/updated` | updateProduct | same shape; `categoryIds` taken from the DB |
| `catalog/product-variant/created` | createProduct, createProductVariant | `{"id","productId","currentVersionId","publiclyVisible":bool}` |
| `catalog/product-variant/updated` | updateProductVariant | `{"id","publiclyVisible":bool}` |
| `catalog/product-variant-version/created` | createProduct, createProductVariant, createProductVariantVersion | `{"id","name","description","version":int,"retailPrice":int,"createdAt":"<ISO_OFFSET_DATE_TIME of the in-memory OffsetDateTime.now(), e.g. 2026-10-13T11:57:34.123456Z>","canBeReturnedForDays":int (omitted when null),"productVariantId","taxRateId","weight":double,"mediaIds":[…]}` |

The publish order per mutation is listed in §8.

### 9.2 Consumed

| Route | Topic | Body | Action | Response |
|---|---|---|---|---|
| `POST /subscription/tax/tax-rate/created` | `tax/tax-rate/created` | CloudEvent (`application/cloudevents+json`), `data: {"id": uuid, …}` (extra fields ignored) | `INSERT INTO TaxRateEntity (id)` | 200, empty body. A duplicate id gives a PK violation → 500 → Dapr redelivers (retries forever on Redis streams). |
| `POST /subscription/media/media/created` | `media/media/created` | `data: {"id": uuid}` | `INSERT INTO MediaEntity (id)` | same |

The body is deserialized with Spring's Jackson into the Dapr `CloudEvent<CreateTaxRateDTO>`, with unknown properties ignored.
There is no transaction and no idempotency.

## 10. Invocations

* Inbound: gateway → `POST /graphql` through the Dapr sidecar (all queries, mutations and `_entities`).
* Outbound: none (no `WebClient`, no `/v1.0/invoke`).

## 11. Environment and configuration

| Env | Used for |
|---|---|
| `SPRING_R2DBC_URL` | e.g. `r2dbc:postgresql://catalog-db:5432/catalog` |
| `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD` | R2DBC and **also Flyway** credentials |
| `SPRING_FLYWAY_URL` | `jdbc:postgresql://catalog-db:5432/catalog` |
| `SPRING_FLYWAY_ENABLED` | `false` is used by the schema-generation script (run without a DB) |
| `DAPR_GRPC_PORT` / `DAPR_HTTP_PORT` / `DAPR_SIDECAR_IP` | Dapr SDK defaults 50001 / 3500 / 127.0.0.1 |
| `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_METRIC_EXPORT_INTERVAL=5000`, `OTEL_LOGS_EXPORTER=none`, `OTEL_TRACES_EXPORTER=none`, `OTEL_SERVICE_NAME=catalog`, `OTEL_INSTRUMENTATION_HTTP_{CLIENT,SERVER}_EMIT_EXPERIMENTAL_TELEMETRY=true` | consumed by the OpenTelemetry **java agent** (`-javaagent:/home/java/opentelemetry-javaagent.jar`). The calibration relies on the agent's HTTP-server metrics, so the image must keep an equivalent agent (follow PORTING.md / image.sh). |

`application.yaml`:
* `spring.main.allow-bean-definition-overriding=true`
* `spring.flyway.locations=classpath:db/migration`
* `management.endpoints.web.base-path=/`
* `graphql.packages=org.misarch.catalog`
* `graphql.federation.enabled=true`

## 12. Quirks to preserve (record, don't fix)

1. Event JSON uses `publiclyVisible`, not `isPubliclyVisible` (§9.1). Null fields are omitted.
2. Events are published inside the DB transaction, before commit. A later publish failure leaves earlier events
   published while the DB rolls back. Consumers may also see an event before the row is visible.
3. `createProductVariantVersion` makes the new version current but publishes no `product-variant/updated` event.
4. `catalog/product/updated` and `catalog/product-variant/updated` are published even when nothing changed.
5. One missing id in a data-loader batch fails every key in that batch (NPE, message `null`). The Categorical/Numerical
   loaders throw ClassCastException for the wrong subtype.
6. `_entities` resolves Product, ProductVariant and ProductVariantVersion without visibility checks, and
   `ProductVariant.product` has none either. `Product.internalName` requires an employee even for public products.
7. `hasNextPage` queries have no ORDER BY. Each connection field runs its own query.
8. Version numbering is `MAX+1` without locking.
9. A duplicate `categoryIds` entry in createProduct fails with a DB unique violation (not a validation message). Duplicate
   `mediaIds` are deduplicated silently.
10. createCategory inserts the category before validating the names (the transaction undoes it).
11. "Tax rate with id … does not exist" has no trailing period. The other messages do.
12. `TaxRate` is resolved through a DB lookup only to return `{id}`. `Media`/`TaxRate` `_entities` are not resolvable.
13. A duplicate replication event (same tax-rate or media id) returns 500, so Dapr redelivers it forever.
14. Description typos in the SDL (§3).
15. In `createdAt`, the event has microsecond precision and a variable fraction length, while GraphQL `DateTime` prints milliseconds (extended-scalars format).

## 13. Parts needing non-trivial migration

1. **QueryDSL / infobip / kapt.** If the foundation drops them (kapt does not work with K2; infobip ties us to Spring Data
   versions), `BaseConnection`, `CategoricalCategoryCharacteristicValueConnection`, the join lambdas (`applyJoin`), the `BaseFilter`/`BaseOrderField` expressions and the `ENTITY` Q-paths need a small replacement. The options are a SQL builder
   over `DatabaseClient` with bind parameters, or `R2dbcEntityTemplate` plus raw SQL for joins. The replacement must keep:
   * the same SQL shape (count / select / exists-like next-page query)
   * identical predicates, order, offset and limit
   * the same number of round trips
   * unquoted PascalCase identifiers, or lower-case names
2. **Naming strategy.** Reproduce `PascalCaseNamingStrategy` (a `NamingStrategy` bean) or put explicit `@Table`/`@Column` on every
   entity. Keep R2DBC `forceQuote=false`.
3. **Native queries**: upsert, `findValidCategoryCharacteristics` (IN-list expansion), `MAX(version)`, the id-only INSERTs. They port
   as-is with `@Query` or `DatabaseClient`.
4. **Transactions with coroutines.** `@Transactional` on suspend functions needs a reactive TM (Spring ≥ 6.1 supports this), or use
   `TransactionalOperator.executeAndAwait`. Publishing must stay inside the transaction (quirk 2).
5. **graphql-kotlin 7 → latest**:
   * The federation `@link` version must stay v2.5 for an empty SDL diff (newer versions default to a higher spec).
   * Check the `FederatedSchemaGeneratorHooks` constructor and `willGenerateGraphQLType`/`didBuildSchema` signatures.
   * `FederatedTypePromiseResolver` and `KotlinDataLoader` APIs.
   * java-dataloader 3 → 4/5 changes `DataLoaderFactory`/`DataLoaderOptions` (immutable builder, `setBatchLoaderContextProvider`).
   * `GraphQLContext.get<CoroutineScope>()`.
   * `@GraphQLIgnore @Autowired` parameter injection (`SpringDataFetcher`).
   * graphql-java ≥ 22 replaced `DataLoaderDispatcherInstrumentation` with a built-in dispatch strategy. Check that mixed
     suspend + `CompletableFuture` loaders still batch and do not hang.
   * The `ExceptionWhileDataFetching` message format.
   * DateTime and UUID scalar output.
6. **Dapr SDK 1.11 → latest.** `@Topic`/`DaprController`/`CloudEvent<T>` come from `dapr-sdk-springboot`; check what libs/common
   provides. The publish serializer must keep the plain-Jackson/NON_NULL semantics. Either publish with a mapper configured like
   `DefaultObjectSerializer`, or annotate the DTOs (`@JsonProperty("publiclyVisible")`) and use `@JsonInclude(NON_NULL)`.
   Using a Kotlin-module mapper unchanged would emit `isPubliclyVisible` and break wire identity.
7. **Flyway 9 → 10+.** Add the `flyway-database-postgresql` module and keep the custom bean semantics: `baselineOnMigrate`, the
   JDBC URL from `SPRING_FLYWAY_URL`, and R2DBC credentials. Spring Boot's own Flyway auto-configuration must not run a second
   time with different credentials.
8. **Spring Boot 3.2 → latest.** Check the actuator base-path, that the health JSON still shows only `status`, and the property relocations (if Boot 4).

**How it was done in the port** (the plan above was followed):

1. QueryDSL/kapt was dropped by the foundation, and the connections were moved onto libs/common `BaseConnection`. The SQL for each case:
   * Predicates: `Condition.eq("t.productId", id)`.
   * Auth filters: `Condition.raw("t.isPubliclyVisible = TRUE")`.
   * Joins:
     * `INNER JOIN ProductToCategoryEntity productToCategory ON productToCategory.productId = t.id` with `productToCategory.categoryId = ?`
     * the mirror-image join for `Product.categories`
     * `INNER JOIN ProductVariantVersionToMediaEntity … ON ….mediaId = t.id` for `medias`
   * Order fields carry their column lists, for example `INTERNAL_NAME("internalName", "id")`.
   * `CommonOrder.toOrderBy("id")` ignores the field, like the original.
   * `CategoricalCategoryCharacteristicValueConnection` runs its three queries through `DatabaseClient` (`COUNT(DISTINCT stringValue)`, `SELECT DISTINCT stringValue … ORDER BY stringValue … LIMIT/OFFSET`, probe `LIMIT 1 OFFSET first+skip`).
2. The naming strategy and Flyway come from libs/common (`MisarchNamingStrategy`, a Flyway bean with `baselineOnMigrate`, JDBC URL from `SPRING_FLYWAY_URL`, R2DBC credentials).
3. The native queries are kept as `@Query` with identical SQL. `upsert` takes the discriminator as `String` (its enum name). The original passed the enum, which Spring converted to the same name.
4. `@Transactional(propagation = REQUIRES_NEW)` stays on `Mutation` (suspend functions, `R2dbcTransactionManager`). Events are still published inside the transaction.
5. graphql-kotlin 11 / graphql-java 26 / java-dataloader 6 / extended-scalars 24:
   * The federation link is pinned to v2.5 by libs/common.
   * The data loaders use libs/common `IdDataLoader`/`BaseIdDataLoader` (same `!!` batch failure).
   * The Categorical and Numerical loaders keep the cast, so a `ClassCastException` fails the batch.
   * `DateTime` output is identical. I checked this by serializing three `OffsetDateTime`s with extended-scalars 21 + graphql-java 21.4 and with 24 + 26.1: both print `2026-10-13T11:57:34.123Z`, `…34.000Z` and `…34.120+02:00`.
   * The error texts `Exception while fetching data (/path) : msg` and graphql-kotlin's `Exception was thrown while trying to resolve federated type, representation=…` are unchanged.
6. Dapr: `@Topic` + `DaprController` come from `dapr-sdk-springboot` 1.18.1 (same code as 1.11). Publishing goes through libs/common `EventPublisher` (HTTP) + `EventJson`. `EventJson` is Jackson 3 configured like the SDK serializer, and `EventPayloadTest` asserts the exact original strings, including key order and `publiclyVisible`.
7. `findById(...).awaitSingle()` on a missing row became `findById(...) ?: throw NoSuchElementException()` (`util/awaitSingle.kt`), so the error text stays identical (`… : null`, verified on the running original, docs/INTEGRATION.md F3). All connections use `PagingValidation.QUERYDSL` (also the custom `CategoricalCategoryCharacteristicValueConnection`), F2.

## 14. Test plan

Tests use Postgres 16 Testcontainers (one container per test class, or shared and stopped after use), a fake Dapr endpoint
from libs/common, and `Authorized-User` headers for anonymous, buyer (`["buyer"]`), employee and admin users.

**Unit**
* `AuthorizedUser` role logic and error messages.
* `duplicates()`.
* Order/filter → SQL builder output, for every order field and direction, the default order, filter + auth combination and joins.
* Event DTO serialization: exact JSON for all 6 payloads, including `publiclyVisible`, the omitted `canBeReturnedForDays`,
  `createdAt` format, and set-deduplicated `categoryIds`/`mediaIds` with order kept.
* Characteristic-value validation: duplicate, invalid, wrong-type and empty-list short circuit.

**Integration (HTTP `/graphql` against the full app + Testcontainers Postgres)**
1. Flyway:
   * On an empty DB it creates exactly the original tables, columns and trigger (compare `information_schema` with a DB migrated by the original SQL).
   * On a DB already migrated by the original (pre-seeded `flyway_schema_history`) it starts without re-running migrations.
2. createCategory:
   * Characteristics are created.
   * Duplicate names → error, and no rows remain.
   * Non-employee → `Unauthorized access…`, and no rows or events.
3. createCategorical/NumericalCategoryCharacteristic: OK case, and an unknown category gives the message.
4. createProduct full flow (data from `testdata/create-test-data.sh`; seed the tax rate and media through the subscription endpoints):
   * The rows, `defaultVariantId` and `currentVersion` are set, version 1, and the characteristic values and media links are stored.
   * The 3 events are published in order with exact payloads.
   * The error cases each roll back with no events: unknown category, duplicate category id, unknown tax rate, unknown media, invalid, duplicate or wrong-type characteristic ids.
5. createProductVariant / createProductVariantVersion:
   * Versions increment.
   * The variant's current version is updated.
   * The events are published, and no `product-variant/updated` event is sent.
   * Unknown parent → message.
6. updateProduct:
   * Partial updates.
   * A default variant from another product → message.
   * Unknown id → NoSuchElement message.
   * The event is sent even without changes, and its `categoryIds` come from the DB.
7. updateProductVariant: visibility and event.
8. Queries:
   * `products`: first/skip/orderBy (ID, INTERNAL_NAME, both directions), filter, `hasNextPage` edge cases (`first` null/0/exact), `totalCount`, and visibility per role.
   * `product(id)`: hidden product as buyer → error, as employee → ok, unknown id → error.
   * `categories`, `category`, `categoryCharacteristic` (resolves to the concrete type).
   * `Category.products` (join, filter and auth filter), `Category.characteristics`.
   * `CategoricalCategoryCharacteristic.values`: distinct, count, order, `hasNextPage`.
   * `Product.variants` (hidden product as buyer → error; hidden variants filtered), `Product.categories`, `defaultVariant` visibility, `internalName` for anonymous, buyer and employee.
   * `ProductVariant.currentVersion` visibility, `versions` (employee only, orders VERSION and CREATED_AT), `product`.
   * `ProductVariantVersion.characteristicValues` (both subtypes, `characteristic` back-reference), `medias` (join), `taxRate`, `createdAt` format.
9. Federation: `_entities` for each resolvable type (including a hidden product without a header, which must succeed), an
   unknown id (whole batch fails), a categorical id given to the Numerical type (error), `Media`/`TaxRate` representations, and `_service{sdl}`.
10. Batching: N `_entities` Product representations, or the `defaultVariant` of N products, → one `IN` query per loader
    (count statements through a statement listener or `pg_stat_statements`).

**Events**
* Fake Dapr endpoint: capture topic + body for every mutation (above). When the fake returns 500, the mutation fails and the DB is rolled back.
* Subscriptions:
  * POST CloudEvent JSON (content type `application/cloudevents+json`) to both routes → 200 and a row is inserted.
  * A duplicate gives a non-2xx response.
  * Extra data fields are ignored.
* `GET /dapr/subscribe` equals the original's JSON (§2, after the §15 check).

**Contract**
* `tools/sdl-diff.sh` shows an empty diff.
* `GET /health` → `{"status":"UP"}`.
* Image built through `tools/image.sh`, started with the original env vars (§11).
* Optional differential test: replay the same GraphQL script against `ghcr.io/misarch/catalog:main` and the port (same seed) and
  diff the responses, ignoring generated ids and timestamps. Run it only outside measurement windows.

## 15. Open items to verify in phase B (outside measurement windows)

1. ~~Run the original jar and capture `/sdl` and `/dapr/subscribe`.~~ Done 2026-10-13 (§2).
2. ~~Confirm the `publiclyVisible` wire name and key order.~~ Done (§9.1).
3. ~~Check how libs/common publishes events and how it exposes `/dapr/subscribe`.~~ It publishes over HTTP with `EventJson` (§13), and `/dapr/subscribe` comes from the Dapr SDK `DaprController`.
4. Not automated: a statement-count check that the data loaders batch, i.e. that N `_entities` produce one `IN` query (test plan item 10). The loaders are the libs/common ones, used as in address.
5. Not done: the differential replay against `ghcr.io/misarch/catalog:main`. Running the original stack is not allowed.

## 16. Deviations from the original

| Deviation | Reason | Expected effect |
|---|---|---|
| Events are published over the Dapr **HTTP** API (`POST /v1.0/publish/pubsub/<topic>`). The original used the SDK's gRPC client. | Project-wide decision (docs/VERSIONS.md) | The CloudEvent that subscribers receive is the same, and so is the payload JSON. The per-publish sidecar call is HTTP/1.1 instead of gRPC, a small latency and CPU difference on mutations (createProduct publishes 3 events). |
| JDK 25, Spring Boot 4.1, graphql-kotlin 11, graphql-java 26, Kotlin 2.4, r2dbc-postgresql 1.1.3, Flyway 13.10 (originally JDK 17 / Boot 3.2 / graphql-kotlin 7 / graphql-java 21 / r2dbc 1.0.4 / Flyway 9.22). OTel agent 2.32.0 (originally 2.15.0, also 2.x semantic conventions). | Uniform up-to-date stack | Runtime performance differs. The behaviour is identical as far as the tests show. |
| The SQL text differs from what QueryDSL generated: alias `t`; no `LIMIT 9223372036854775807` when `first` is null (no LIMIT at all); the `hasNextPage` probe selects `1` instead of all entity columns; named binds. | QueryDSL was removed | Same rows, same order, same number of queries per field. The next-page probe is marginally cheaper. |
| `CategoryCharacteristicValueRepository.upsert` binds the discriminator as a `String`. | Avoids relying on enum conversion of `@Query` parameters | None (the same value is stored). |
| Database error texts (for example a duplicate `categoryIds` entry in `createProduct`, or a duplicate replication event) come from Spring Framework 7 / r2dbc-postgresql 1.1.3. The PostgreSQL part (`duplicate key value violates unique constraint "…"`) is the same, but the Spring wrapper text around it may differ from Boot 3.2's. | Library upgrade | GraphQL error message text for DB-level failures only. Status codes are unchanged (subscription → 500 → Dapr redelivery). |
| The `/dapr/config`, `/healthz` and actor routes are served by `dapr-sdk-springboot` 1.18.1 instead of 1.11. | Upgrade | Identical responses: `/dapr/config` → `{"entities":[]}`, `/healthz` → 200. |

No deviation in: the GraphQL SDL (live and `schemas` diff empty), app id, topics, routes, payload JSON, the subscription
status codes, env vars, port 8080, `/health`, tables and columns (the migrations are byte-identical), authorization, validation
messages, event order, and the transaction scope.


## Integration fixes (2026-10-13, docs/INTEGRATION.md §4.1)

Found by the whole-system differential run against the running original:
- F1: events are decoded with Jackson-2 semantics for primitives (`spring.jackson.deserialization.fail-on-null-for-primitives: false`): a missing/null primitive becomes 0/false instead of HTTP 400, like the original (Spring Boot 3.2).
- F2: all connections use `PagingValidation.QUERYDSL` (`first: 0` → `Limit must be greater than 0.`, negative → `Offset must not be negative.`), verified on the running original.
- F3: a missing row throws `NoSuchElementException()` without message → `Exception while fetching data (/x) : null`, verified on the running original (was `… : No value received via onNext for awaitSingle`).
