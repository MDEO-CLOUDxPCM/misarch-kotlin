# inventory — port specification

Original: `misarch/inventory` (TypeScript, NestJS 10, @nestjs/graphql 12 + Apollo Server 4 federation v2,
Mongoose 8.0.3 on MongoDB 6 replica set, class-validator 0.14.0, @dapr/dapr 3.3). Commit `f55f03e` (2025-06-29), the
version of the `ghcr.io/misarch/inventory:main` image that the calibration measures.

Everything below was taken from the code and then **verified against the original image** (`ghcr.io/misarch/inventory:main`
run in an isolated Docker network with its own MongoDB 6 replica set, a fake Dapr sidecar and recorded HTTP
exchanges; see §11). "Quirk" marks behaviour that looks wrong but must be reproduced (it is part of the baseline that
we model and measure).

## 1. Runtime contract

| Item | Value |
|---|---|
| Dapr app-id | `inventory` |
| HTTP port | `8080` (GraphQL, Dapr subscriptions, health) |
| GraphQL endpoint | `POST /graphql` (Apollo Server 4; GET only with CSRF-preflight header) |
| Health | `GET /health` → `200 {"status":"OK"}` |
| Subscriptions | `GET /dapr/subscribe` (programmatic, §5.1) |
| Dapr sidecar used for publishing | `http://127.0.0.1:3500` (hard-coded host and port, HTTP) |
| Pub/sub component | `pubsub` |
| Body parsing | JSON for `application/json` and `application/cloudevents+json`; Express default limit 100 kB (larger bodies → 413) |
| Outgoing service invocations | none |
| Served service invocations | only GraphQL via Dapr (`/v1.0/invoke/inventory/method/graphql`), see §4.3 |

### Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `DATABASE_URI` | `mongodb://localhost:27017` | Mongo connection (compose: `mongodb://inventory-db:27017`) |
| `DATABASE_NAME` | `test` | Mongo DB name (compose does not set it → `test`) |
| `NODE_ENV` | unset | `production` switches logging to rotating files; otherwise console |
| `OTEL_*`, `NODE_OPTIONS` | see compose | OpenTelemetry metrics export (HTTP server metrics, every 5 s, to `${OTEL_EXPORTER_OTLP_ENDPOINT}/v1/metrics`); traces disabled (`OTEL_TRACES_EXPORTER=none`) |

Port: keep the env var names that make sense for the uniform stack. The Mongo variables are replaced by the
Postgres variables defined in `docs/PORTING.md`; `DATABASE_URI`/`DATABASE_NAME` are not used by the port (documented
deviation, the database itself is replaced). OTEL variables keep their meaning.

## 2. GraphQL schema (federation v2)

The SDL that the original prints (`src/inventory.gql`, `printSubgraphSchema`) is the contract; the port must produce
an empty diff against `reference/sdl/inventory.graphql` (tools/sdl-diff.sh). Summary:

- `scalar UUID` — "A universally unique identifier compliant UUID Scalar". Serialize/parseValue/parseLiteral all
  check `^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$` case-insensitively and return the string
  **unchanged** (case preserved, no normalisation). Error message `invalid uuid`; a non-string literal gives
  `UUID must be a string.`.
- `type ProductVariant @key(fields: "id")` (description "Foreign type ProductVariant"): `id: UUID!`,
  `productItems(skip: Int = 0, first: Int = 2147483647, orderBy: ProductItemOrder, filter: ProductItemOfProductVariantFilter): ProductItemConnection`
  (nullable), `inventoryCount: Int!`.
- `type ProductItem @key(fields: "id")`: `id: UUID!`, `productVariant: ProductVariant!`,
  `inventoryStatus: ProductItemStatus!`, `orderId: UUID`.
- `type ProductItemConnection { nodes: [ProductItem!], totalCount: Int!, hasNextPage: Boolean! }`.
- `enum ProductItemStatus { IN_STORAGE RESERVED IN_FULFILLMENT SHIPPED DELIVERED RETURNED LOST }`.
- `enum ProductItemOrderField { ID }`, `enum OrderDirection { ASC DESC }`,
  `input ProductItemOrder { field: ProductItemOrderField, direction: OrderDirection }`.
- `input ProductItemFilter { productVariant: UUID, inventoryStatus: ProductItemStatus }`,
  `input ProductItemOfProductVariantFilter { inventoryStatus: ProductItemStatus }`.
- Query: `productItems(skip, first, orderBy, filter: ProductItemFilter): ProductItemConnection!`,
  `productItem(id: UUID!): ProductItem!` (argument description "UUID of the user" — sic).
- Mutation: `createProductItemBatch(input: CreateProductItemBatchInput!): [ProductItem!]!`,
  `updateProductItem(input: UpdateProductItemInput!): ProductItem!`, `deleteProductItem(id: UUID!): ProductItem!`,
  `reserveProductItemBatch(input: ReserveProductItemsBatchInput!): [ProductItem!]!`.
- Inputs: `CreateProductItemBatchInput { productVariantId: UUID!, number: Int! }`,
  `UpdateProductItemInput { id: UUID!, productVariantId: UUID!, inventoryStatus: ProductItemStatus! }`,
  `ReserveProductItemsBatchInput { productVariantId: UUID!, number: Int!, orderId: UUID! }`.
- Federation: `extend schema @link(url: "https://specs.apollo.dev/federation/v2.3", import: [...])`, `_service`,
  `_entities` for `ProductVariant` and `ProductItem` (both resolvable keys). Descriptions are part of the SDL and must
  match character for character (including typos such as "productVartiantId", "Decending").

### Federation entity resolution

- `ProductVariant`: no reference resolver → the representation `{__typename, id}` is the parent object (the default
  Apollo behaviour). No authorization. Used synchronously by **order** during `createOrder`:
  `_entities(representations:[{__typename:"ProductVariant", id}]) { __typename ... on ProductVariant { id inventoryCount } }`
  via `POST localhost:3500/v1.0/invoke/inventory/method/graphql` **without** `Authorized-User` header. This path must
  keep working without the header.
- `ProductItem`: reference resolver `findById(id)` guarded by roles `employee`/`admin` (guards run on reference
  resolvers because `fieldResolverEnhancers: ['guards']`). Not found → `ProductItem with ID "<id>" not found`.

### SDL references

`tools/sdl-diff.sh` against `reference/sdl` (live `_service` SDL of the running original): no difference.
Against `reference/schemas` (misarch/schemas, produced by the `graphql-schema-transform` action): that file marks
`ProductItemConnection` `@shareable`, the live service does not; the live SDL wins (PORTING.md §4), the port has no `@shareable`.

## 3. Authorization

Header `Authorized-User` (set by the gateway, JSON `{"id": "<uuid>", "roles": ["buyer"|"employee"|"admin", ...]}`).
Guard (`RolesGuard`, global) for every resolver that has `@Roles(...)`:

1. no roles metadata → allowed;
2. header missing or empty string → deny → `ForbiddenException("Forbidden resource")`;
3. `JSON.parse(header)` (invalid JSON → the `SyntaxError` becomes the GraphQL error);
4. allowed iff `requiredRoles.some(r => roles.includes(r))` where `roles = parsed.roles`. Consequences (quirks):
   `roles` missing → `TypeError: Cannot read properties of undefined (reading 'includes')`; `roles` given as a JSON
   string → substring test (`"superadmin".includes("admin")` is true); non-array object → `roles.includes is not a function`.

| Operation | Required role (any of) |
|---|---|
| `Query.productItems`, `Query.productItem` | employee, admin |
| `Mutation.createProductItemBatch`, `updateProductItem`, `reserveProductItemBatch` | employee, admin |
| `Mutation.deleteProductItem` | admin |
| `ProductVariant.productItems` (field) | employee, admin — denied → field is `null` + error (field is nullable) |
| `ProductVariant.inventoryCount` | public |
| `_entities` ProductItem | employee, admin |
| `_entities` ProductVariant, `ProductItem.productVariant` | public |

## 4. Resolvers

### 4.1 Common: argument validation and connections

`@Args()` classes are validated by the global `ValidationPipe` (class-validator, `forbidUnknownValues: false`,
no transform → resolvers see the raw GraphQL argument objects):

- `skip`: `@Min(0)` → message `skip must not be less than 0`; `first`: `@Min(1)` → `first must not be less than 1`.
  Explicit `null` also fails (`Min` rejects non-numbers). Messages are collected in declaration order (skip, first).
  Failure → `BadRequestException`; GraphQL error message `Bad Request Exception`, `extensions.code` `BAD_REQUEST`,
  `extensions.originalError = {"message":[...],"error":"Bad Request","statusCode":400}`.
- Nested `orderBy`/`filter` and mutation inputs are **not** validated (no decorators).

`buildConnection(selection, args)`: `selection` = names of the **Field** selections directly under the connection
field of the first field node (fragments and inline fragments are ignored; aliases resolve to the field name):

- `nodes` selected → `nodes = find(args)`; otherwise `nodes` stays `null`.
- `totalCount` or `hasNextPage` selected → `totalCount = count(args.filter)`,
  `hasNextPage = skip + first < totalCount` (arbitrary precision in JS; port must use 64-bit arithmetic).
- If the client selects the connection fields only through fragments, `totalCount` is never computed and the
  non-null `totalCount`/`hasNextPage` raise "Cannot return null for non-nullable field ..." (quirk, reproduce).
  **FIXED**: fields selected through fragments count (`selectionSet.immediateFields`).

`find(args)`:
- order: if `orderBy` missing or one of its fields missing, defaults are filled per field: field `ID` (`_id`),
  direction `ASC`. Sort `_id` ascending/descending (Mongo binary string order → port: `ORDER BY id COLLATE "C"`).
- `limit(first).skip(skip)`.
- **Quirk (filter mapping):** the filter is translated by `buildQuery`, which maps
  `filter.productVariant` → Mongo field `status` and `filter.inventoryStatus` → Mongo field `createdAt`. Neither field
  exists in product item documents (Mongoose 8: `strictQuery` is `false`, so the unknown keys reach MongoDB). Hence:
  if `filter.productVariant` or `filter.inventoryStatus` is truthy, `nodes` is always `[]`; otherwise all items are
  returned. Falsy (absent or `null`) filter fields are ignored for `nodes`.

`count(filter)` uses the **raw** filter object (correct field names, no truthiness check):
`countDocuments({productVariant?, inventoryStatus?})`. Keys that are present with value `null` match documents where
that field is `null` or missing (relevant for "phantom" items, §6). `filter` absent → count all.

So `productItems(filter:{inventoryStatus:RESERVED}){nodes{id} totalCount}` returns `nodes: []` and the real number
of reserved items. This must be reproduced exactly.

### 4.2 Query and Mutation

| Field | Behaviour |
|---|---|
| `productItems(skip, first, orderBy, filter)` | §4.1. |
| `productItem(id)` | `findById(id)`; missing → `NotFoundException('ProductItem with ID "<id>" not found')` (GraphQL message = that text, code per §9). |
| `createProductItemBatch({productVariantId, number})` | 1. `ProductVariantPartial` with `_id = productVariantId` must exist (exact, case-sensitive string match), else `NotFoundException('ProductVariant with ID "<id>" not found')`. 2. In one transaction, create `number` items sequentially: `{_id: uuidv4(), productVariant: productVariantId, inventoryStatus: IN_STORAGE}` (no `orderId`). `number <= 0` → returns `[]` and creates nothing. Returns the created items in creation order. Any error aborts the whole batch. |
| `updateProductItem({id, productVariantId, inventoryStatus})` | 1. variant check as above (before the item lookup). 2. `findOneAndUpdate({_id: id}, {$set: {productVariant: productVariantId, inventoryStatus}}, {new: true})` (the `overwrite: true` option is ignored by Mongoose 8; unknown keys `id`, `productVariantId` are stripped). `orderId` is **kept**. Missing → `NotFoundException('ProductItem with ID "<id>" not found')`. Returns the updated item. |
| `deleteProductItem(id)` | `findByIdAndDelete(id)`; returns the deleted item. **Quirk:** missing item → resolver returns `null` for the non-null field → error `Cannot return null for non-nullable field Mutation.deleteProductItem.` and `data: null`. No event. |
| `reserveProductItemBatch({productVariantId, number, orderId})` | §4.4. **Quirk:** always returns `[]` on success (the reload query is `find({filter: {_id: {$in: ids}}})`, which matches nothing). |

### 4.3 Field resolvers

| Field | Behaviour |
|---|---|
| `ProductItem.id` | `_id`. |
| `ProductItem.productVariant` | `{__typename: "ProductVariant", id: item.productVariant}`; no lookup. For a phantom item (no `productVariant`, §6) `id` is null → `Cannot return null for non-nullable field ProductVariant.id.` (bubbles to the nearest nullable parent). |
| `ProductItem.inventoryStatus`, `orderId` | stored values (`orderId` may be `null`). |
| `ProductVariant.productItems(skip, first, orderBy, filter)` | Guarded (§3), args validated (§4.1). Calls `buildConnection` with `filter = {productVariant: parent.id}` — the `filter` **argument is ignored** (overwritten). Because of the `buildQuery` quirk, `nodes` is always `[]`, while `totalCount` counts **all** items of the variant (any status). |
| `ProductVariant.inventoryCount` | `countDocuments({productVariant: id, inventoryStatus: IN_STORAGE})`. Public. |

### 4.4 Reservation (shared by the mutation and the `order-created` handler)

`reserveProductItemBatch(productVariantId, number, orderId)`:
1. Start a transaction with snapshot read concern.
2. `find({productVariant, inventoryStatus: IN_STORAGE}).limit(number)` in natural (insertion) order. Mongo limit
   semantics: `number = 0` → **no limit** (all available items are reserved, quirk); `number < 0` → `|number|` items.
3. If fewer than `number` items were found → `NotFoundException('Not enough product items available for product variant "<id>"')`,
   transaction aborted.
4. `updateMany({_id: {$in: ids}}, {$set: {inventoryStatus: RESERVED, orderId}})` in the transaction, commit.
   No existence check of the variant partial, no check of `orderId`.
5. Return `find({filter: …})` → `[]` (quirk, see 4.2).

Concurrency (verified: 6 parallel reservations of 5 items of the same variant → 1 succeeds, 5 fail with
`WriteConflict error: this operation conflicted with another operation. Please retry your operation or multi-document transaction.`):
two transactions that pick the same items conflict; MongoDB aborts the later writer (no retry in the code) → that
reservation fails (in `order-created` it counts as a failed variant). Port: `REPEATABLE READ` transaction, no retry;
a serialization failure (`40001`) is reported with the same message. Port difference: Postgres lets the second writer
wait for the first one's commit before failing, MongoDB fails at once; if the first one rolls back, Postgres proceeds.
**FIXED** (docs/fixes/inventory.md): READ COMMITTED, one `UPDATE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED LIMIT n)
RETURNING *`; concurrent reservations take different items and only fail when the stock is really insufficient.

### 4.5 Release and status updates (event handlers)

`releaseProductItemBatch(orderId)`:
1. `ids = find({orderId})` (any status).
2. `updateMany({_id: {$in: ids}}, {$set: {inventoryStatus: IN_STORAGE, orderId: null}}, {upsert: true})`.
   **Quirk (phantom items):** if `ids` is empty, the upsert inserts a new document
   `{_id: uuidv4(), inventoryStatus: "IN_STORAGE", orderId: null}` without `productVariant` (Mongoose
   `setDefaultsOnInsert` adds `_id`; `$in: []` contributes no equality field). The port inserts the same phantom row
   (`product_variant = NULL`).
3. Returns `find({_id: {$in: ids}})` (released items, natural order).

`updateOrderProductItemsStatus(orderId, status)`: `updateMany({orderId}, {$set: {inventoryStatus: status}})`,
no upsert, no status precondition.

## 5. Events

### 5.1 `GET /dapr/subscribe` (must be returned verbatim, including the inconsistent key casing)

```json
[
  {"pubsubName":"pubsub","topic":"catalog/product-variant/created","route":"product-variant-created"},
  {"pubsubName":"pubsub","topic":"order/order/created","route":"order-created"},
  {"pubsubname":"pubsub","topic":"payment/payment/payment-enabled","route":"payment-enabled"},
  {"pubsubname":"pubsub","topic":"payment/payment/payment-failed","route":"payment-failed"},
  {"pubsubname":"pubsub","topic":"shipment/shipment/status-updated","route":"shipment-status-updated"},
  {"pubsubname":"pubsub","topic":"shipment/shipment/created","route":"shipment-created"},
  {"pubsubname":"pubsub","topic":"discount/order/validation-failed","route":"discount-validation-failed"}
]
```
(Dapr decodes the key case-insensitively, so all seven subscriptions are active.)

All routes are `POST /<route>`; the handler reads `body.data` (CloudEvent). NestJS answers `201` with an empty body
when the handler returns nothing. Dapr semantics: 2xx = success (a non-JSON or non-`{status}` body is ignored),
404 = drop, anything else = retry (redelivery forever with Redis streams, no resiliency policy). Therefore the
**status code per case is part of the contract**.

Validation (`ValidationPipe` on `body.data` with the DTO class): a failure answers
`400 {"message":[...],"error":"Bad Request","statusCode":400}` → Dapr retries. `data` missing or `null` → validated as `{}`.
Message format follows class-validator + NestJS flattening (nested errors are prefixed `orderItems.0.`; a parent's own
constraint is dropped when it has nested errors; within one property the constraints appear bottom-up, e.g.
`count must not be less than 1`, `count must be an integer number`). The exact outputs are fixed by the corpus (§11).

### 5.2 Consumed

| Route | DTO validation | Behaviour | Response |
|---|---|---|---|
| `product-variant-created` (`catalog/product-variant/created`, payload `{id, productId, currentVersionId, isPubliclyVisible}`) | none | `ProductVariantPartial.create({_id: data.id})` **not awaited**. Duplicate id (redelivery) → duplicate-key error after the response → **process terminates** (verified, §8 D1). `data.id` missing → a partial with a random id is created. `data` missing → TypeError → 500 (retry). | 201 |
| `order-created` (`order/order/created`, payload = OrderDTO §5.4) | OrderDTO (§5.4); 400 on failure | For every `orderItems[i]` concurrently (`Promise.all`): `reserveProductItemBatch(productVariantId, count, order.id)`; a thrown error = failure for that variant (logged). If ≥1 failed: publish `reservation-failed` (not awaited) with the failed variant ids in **order-item order**, then **await** `releaseProductItemBatch(order.id)` (releases what the successful items reserved; nothing reserved → phantom item, §4.5) and return the released items as the response body. Else publish `reservation-succeeded` (not awaited). An exception outside the per-item handling is *returned* (not thrown) as `UnprocessableEntityException` → 201 with the serialized exception object as body. Empty `orderItems` → success event. | 201 |
| `payment-enabled` (`payment/payment/payment-enabled`, `{order}`) | `@IsObject() order` | **awaited** `updateMany({orderId: order.id}, {inventoryStatus: IN_FULFILLMENT})` | 201 |
| `payment-failed` (`payment/payment/payment-failed`, `{order}`) | `@IsObject() order` | `releaseProductItemBatch(order.id)` **not awaited** (phantom item if nothing is assigned to the order any more) | 201 |
| `shipment-created` (`shipment/shipment/created`) | ShipmentStatusUpdatedDto: `id` string, `orderId`/`returnId` optional strings (null ok), `status` ∈ {PENDING, IN_PROGRESS, DELIVERED, FAILED}, `orderItemIds` array | `orderId` falsy (return shipment) → nothing. Else **awaited** `updateMany({orderId}, {inventoryStatus: SHIPPED})` | 201 |
| `shipment-status-updated` (`shipment/shipment/status-updated`) | same DTO | `orderId` falsy → nothing. `DELIVERED` → items `DELIVERED`; `FAILED` → items `LOST`; other statuses → nothing. Awaited. | 201 |
| `discount-validation-failed` (`discount/order/validation-failed`, `{order, failingDiscountIds}`) | none | `releaseProductItemBatch(order.id)` **not awaited** (phantom item for an order without items, verified); `order` missing → TypeError → `500 {"statusCode":500,"message":"Internal server error"}` | 201 |

Idempotency: none. Redelivered `order-created` reserves again (a second set of items for the same order if stock
allows); redelivered release events release again (and may create phantom items). Statuses are overwritten without
checking the current state (e.g. a late `payment-failed` puts SHIPPED items back to IN_STORAGE).

### 5.3 Published (via `POST http://127.0.0.1:3500/v1.0/publish/pubsub/<topic>?`, `Content-Type: application/json`, errors only logged)

| Topic | When | Payload |
|---|---|---|
| `inventory/product-item/reservation-succeeded` | all order items reserved | `{"order": <order object exactly as received>}` |
| `inventory/product-item/reservation-failed` | ≥1 order item failed | `{"order": <order as received>, "failedProductVariantIds": ["<uuid>", ...]}` |

The order object is passed through untouched (all received fields, including ones the DTO does not declare such as
`paymentAuthorization`, `vatNumber`). The port keeps it as a raw JSON tree. Publishing is fire-and-forget: the
handler does not wait for the publish and a failed publish is only logged.

### 5.4 OrderDTO validation (inventory version)

Top level: `id` UUID, `userId` UUID, `createdAt` ISO-8601 date string (`IsDateString`), `orderStatus` ∈ {PENDING,
PLACED, REJECTED}, `compensatableOrderAmount` integer, `placedAt` optional ISO date string, `rejectionReason`
optional ∈ {INVALID_ORDER_DATA, INVENTORY_RESERVATION_FAILED}, `orderItems` array (each validated),
`shipmentAddressId`/`invoiceAddressId`/`paymentInformationId` UUID, `payment_authorization` nested object
(only if present — the real payload uses `paymentAuthorization`, so it is never checked), `vat_number` unchecked.
Item: `id`, `productVariantVersionId`, `productVariantId`, `taxRateVersionId`, `shoppingCartItemId`,
`shipmentMethodId` UUID; `createdAt` ISO date string; `count` integer ≥ 1; `compensatableAmount` integer ≥ 0;
`discountIds` array of **UUID v4**. `IsUUID()` = any version (validator.js `all`); `IsOptional` accepts `null` and
missing. Unknown properties are allowed. Exact class-validator messages are listed in the port's validator; the
order of messages follows property declaration order.

## 6. Persistence (Mongo → Postgres)

| Mongo collection | Document | Postgres table |
|---|---|---|
| `productitems` | `{_id: string uuid, productVariant: string (Mixed), inventoryStatus: string, orderId?: string\|null}` (no `__v`) | `ProductItemEntity(id TEXT COLLATE "C" PK, productVariant TEXT NULL, inventoryStatus TEXT NOT NULL, orderId TEXT NULL, seq BIGINT IDENTITY)` |
| `productvariantpartials` | `{_id: string, __v: 0}` | `ProductVariantPartialEntity(id TEXT COLLATE "C" PK)` |

- Ids are kept as **text** (Mongo compares strings case-sensitively; UUID scalars preserve case).
- `seq` reproduces Mongo's natural (insertion) order for unsorted reads (reservation picking, release result).
- `product_variant` is nullable because of phantom items; `order_id` null = not reserved.
- Indexes: `(productVariant, inventoryStatus)`, `(orderId)`. Migration: `services/inventory/src/main/resources/db/migration/V1__init.sql`.
- PORTING.md suggests `UUID` id columns; `TEXT` is used deliberately because the original stores and compares ids as
  strings (an upper-case UUID input does not find a lower-case id; partial ids from events need not be UUIDs).
- Transactions: batch creation and each reservation run in one transaction (reservation: REPEATABLE READ).

## 7. Logging and telemetry

Logs are not part of the contract. Metrics: the original exports Node HTTP server metrics via OTLP; the port uses
the uniform stack's telemetry (see PORTING.md).

## 8. Quirks and defects (originally reproduced; FIXED ones see docs/fixes/inventory.md) and deliberate deviations

Reproduced:
1. **FIXED** (nodes filtered like totalCount). Filter mapping bug: `nodes` empty whenever a filter field is set; `ProductVariant.productItems.nodes` always empty; `totalCount` correct (§4.1, 4.3).
2. **FIXED** (variant + `inventoryStatus` filter for nodes and totalCount). `ProductVariant.productItems` ignores its `filter` argument; totalCount includes all statuses.
3. **FIXED** (returns the reserved items; `number <= 0` reserves nothing). `reserveProductItemBatch` mutation returns `[]`; `number = 0` reserves all available items; negative → |n|.
4. **FIXED** (no insert). Release with nothing assigned inserts a phantom `IN_STORAGE` item without product variant (§4.5).
5. **FIXED** (`ProductItem with ID "<id>" not found`). `deleteProductItem` of a missing id → non-null error, `data: null`.
6. `updateProductItem` keeps `orderId`; no state machine anywhere (statuses are overwritten blindly).
7. **FIXED** for `order-created` (reservation per order tops up only missing items) and `product-variant-created` (awaited, `ON CONFLICT DO NOTHING`); releases and status updates are naturally idempotent. No idempotency for any event; product-variant partial creation is fire-and-forget.
8. **FIXED** for internal errors (now 500, Dapr redelivers); the failure path still answers 201 with the released items. `order-created` failure path answers 201 with the released items; internal errors are returned as a 201 body.
9. Subscription list with mixed `pubsubName`/`pubsubname` keys.
10. Role check via `includes` (array membership or substring for a string).
11. `reservation-failed` has no consumer in MiSArch (not our concern; publish anyway).

Deviations (cannot or should not be reproduced):
- D1 **FIXED at the sources** (partial creation, releases and the reservation publish are awaited; failures answer 500 instead of terminating the process; the terminator remains for other detached work). (reproduced, switchable) Unhandled promise rejections terminate the original process (Node 18, no handler):
  verified — a duplicate `product-variant-created` answers 201 and then the container exits with
  `MongoServerError: E11000 duplicate key error` and is restarted by Docker. Sources: fire-and-forget partial creation
  (duplicate id, DB error) and fire-and-forget releases (`payment-failed`, `discount-validation-failed`) failing.
  The port logs the error and terminates the JVM (`halt(1)`) after the response was sent; set
  `MISARCH_EXIT_ON_UNHANDLED_ERROR=false` to only log. JVM restart time differs from Node's (~2 s).
- D2 Database error texts: the Mongo `WriteConflict` message of a failed concurrent reservation is reproduced
  verbatim (`WriteConflict error: this operation conflicted with another operation. Please retry your operation or multi-document transaction.`)
  for Postgres serialization failures; other driver/DB error texts differ. JS TypeError/SyntaxError texts listed in this
  spec are reproduced.
- D3 `extensions.stacktrace` (Apollo includes JS stack traces because `NODE_ENV` is unset) is not reproduced.
- D4 GraphQL parse/validation-phase errors (invalid UUID literal or variable, unknown fields) are produced by
  graphql-java/graphql-kotlin: different texts, and HTTP 200 instead of Apollo's 400 for literal errors. Execution
  errors (everything in §4, §9) are identical. The response always contains `data` (also `null`), Apollo omits it
  for validation errors; JSON key order (`data` before `errors`) differs.
- D5 For a set filter field the original runs a Mongo query on a non-existent field (a collection scan matching
  nothing); the port answers `[]` without a query. Same result, lower cost.
- D6 Mongo `undefined` filter values (e.g. an order event without `id`) are treated like `null` (not verified
  against the original; unreachable with the real producers).
- D7 Apollo federated tracing (`ftv1`) is not supported (the gateway does not request it).

## 9. GraphQL error shape (original, verified)

Errors follow Apollo Server 4 + `@nestjs/apollo` 12 formatting (reproduced by `NestGraphQLErrors` in libs/common `org.misarch.common.nest`; graphql-java's
`NonNullableFieldWasNullError` texts are rewritten to graphql-js' `Cannot return null for non-nullable field T.f.`) (`message`, `locations`, `path`, `extensions`):

| Error source | `extensions` (stacktrace omitted) |
|---|---|
| `BadRequestException` (args validation) | `{"code":"BAD_REQUEST","originalError":{"message":[...],"error":"Bad Request","statusCode":400}}`, message `Bad Request Exception` |
| `UnauthorizedException` | `{"code":"UNAUTHENTICATED","originalError":{"message":"…","error":"Unauthorized","statusCode":401}}` |
| `ForbiddenException` (guard) | `{"code":"FORBIDDEN","originalError":{"message":"Forbidden resource","error":"Forbidden","statusCode":403}}` |
| other `HttpException`, e.g. `NotFoundException` | `{"code":"INTERNAL_SERVER_ERROR","status":404,"originalError":{"message":"…","error":"Not Found","statusCode":404}}` |
| any other error (TypeError, DB error, non-null violation) | `{"code":"INTERNAL_SERVER_ERROR"}` |

`extensions.stacktrace` (JS stack, present because `NODE_ENV` is unset) is not reproduced (D3). HTTP status is 200 for
execution errors. Invalid UUID **literal** → HTTP 400, code `GRAPHQL_VALIDATION_FAILED`, message
`Expected value of type "UUID!", found "nope"; invalid uuid`; invalid UUID **variable** → HTTP 200 (sic),
code `BAD_USER_INPUT`, message `Variable "$id" got invalid value "nope"; Expected type "UUID". invalid uuid`.
Non-null violations bubble per GraphQL spec (e.g. `Cannot return null for non-nullable field Mutation.deleteProductItem.`).
Unknown REST routes: `404 {"message":"Cannot GET /x","error":"Not Found","statusCode":404}`; unhandled errors in
REST/event handlers: `500 {"statusCode":500,"message":"Internal server error"}`.

## 10. Test plan (equivalence)

Unit (no DB):
- UUID scalar: accepts lower/upper case, preserves case; rejects malformed with `invalid uuid`.
- OrderDTO validator: the real order payload (fixture captured from order's serializer: camelCase, `paymentAuthorization: {"cVC":123}`, `rejectionReason: null`, `vatNumber: null`) passes; each rule fails with the class-validator message (count 0, non-v4 discount id, bad date string, missing items array).
- Shipment DTO validator: PENDING/DELIVERED/FAILED pass, unknown status fails, `orderId: null` passes.
- Role guard: missing header, empty header, roles array, roles string substring, missing roles (TypeError text).
- `queryKeys`-equivalent selection extraction ignores fragments; aliases use field names.

Integration (Testcontainers Postgres + Flyway, GraphQL over HTTP):
- `createProductItemBatch`: unknown variant → NotFound message; case-sensitive variant id; number 0/negative → `[]`; 3 → 3 items IN_STORAGE, no orderId, insertion order.
- `productItems`: no filter → all sorted by id ASC/DESC, skip/first, defaults; `filter.productVariant` → `nodes: []`, `totalCount` = variant count; `filter.inventoryStatus` → `nodes: []`, correct count; `filter:{productVariant:null}` → all nodes, count of phantom items; only `nodes` selected → no count query required; fragment-only selection → non-null error; skip −1 / first 0 → `Bad Request Exception` with messages; hasNextPage arithmetic with first = 2147483647 (no overflow).
- `productItem` found/not found; `updateProductItem` keeps orderId, variant check first, not found; `deleteProductItem` found / missing (non-null error, data null); role matrix incl. admin-only delete.
- `_entities` ProductVariant without header → `inventoryCount` (only IN_STORAGE counted); `productItems` → nodes `[]`, totalCount = all statuses, forbidden without role (null + error); `_entities` ProductItem guarded.
- `reserveProductItemBatch`: success returns `[]` and marks oldest `n` IN_STORAGE items RESERVED with orderId; insufficient → NotFound text, nothing changed; number 0 → all reserved; two concurrent reservations on the same items → exactly one succeeds.
- `_service { sdl }` equals reference (tools/sdl-diff.sh).

Validation corpus (unit): every case of `reference/inventory-validation-corpus.json` (generated from the original DTOs)
must produce the identical OK / message array with the port's validators.

Fire-and-forget failures: a failing detached task invokes the process terminator (replaced by a recording fake in
tests); with `MISARCH_EXIT_ON_UNHANDLED_ERROR=false` it only logs.

Event handlers (CloudEvent POSTed to the route; fake Dapr HTTP endpoint records publishes):
- `/dapr/subscribe` byte-equal JSON (modulo whitespace).
- product-variant-created creates the partial (201); duplicate → 201, still one partial.
- order-created: all reservable → `reservation-succeeded` with the order passed through unchanged (extra fields preserved); one item short → `reservation-failed` with `failedProductVariantIds`, other items released, response body = released items; all short → phantom item created; invalid DTO → 400 and no DB change.
- payment-enabled → RESERVED → IN_FULFILLMENT for that order only; payment-failed / discount-validation-failed → items IN_STORAGE, orderId null (eventually; fire-and-forget), and phantom when none; shipment-created with orderId → SHIPPED, without orderId → no change; status-updated DELIVERED/FAILED/IN_PROGRESS.
- Missing `data` on the routes that dereference it → 500 (retry), validated routes → 400.
- `GET /health` → `{"status":"OK"}`.

## 11. Evidence

Verified on 2026-10-13 against `ghcr.io/misarch/inventory:main` (Node 18.20.8, mongoose 8.0.3, @nestjs/apollo 12.0.11,
class-validator 0.14.0, validator 13.11.0) with MongoDB 6.0 (replica set) in an isolated network:
- `strictQuery` is false: `{status: …}`, `{createdAt: …}`, `{filter: {_id: {$in: …}}}` reach MongoDB unchanged and match nothing.
- `findOneAndUpdate(..., {overwrite: true})` → plain `$set` of schema paths (`id`, `productVariantId` stripped).
- `updateMany({_id: {$in: []}}, …, {upsert: true})` inserts `{_id: <uuid>, inventoryStatus: "IN_STORAGE", orderId: null}`.
- `limit(0)` returns everything, `limit(-2)` returns 2; unsorted reads return insertion order.
- `@Prop() productVariant: ProductVariant` is `Mixed` (a class without `@Prop`s) → stored as the plain id string.
- Recorded GraphQL responses (all error cases of §4, §9), event handler status codes/bodies, the publish requests
  (`POST /v1.0/publish/pubsub/<topic>?`, `Content-Type: application/json`, body = `{"order":…}` with the received order
  verbatim, keys in received order) and the crash on a duplicate partial.
- A validation corpus (162 inputs → exact `message` arrays) produced by running the original compiled DTO classes
  through the original `ValidationPipe`; it is a test fixture of the port (`src/test/resources/reference/`).
