# Service spec: `return`

Original: [MiSArch/return](https://github.com/MiSArch/return). Stack: Kotlin 1.9.23, Spring Boot 3.2.4, graphql-kotlin 7.0.2,
R2DBC with infobip QueryDSL 9.0.2, Dapr Java SDK 1.11.0, JDK 17. Package `org.misarch.returns`.
Reference SDL: `return.graphql` of [MiSArch/schemas](https://github.com/MiSArch/schemas). It matches the code.

## 1. Role in the system

- Owns `Return` (reason, orderId, refundedAmount, createdAt).
- Keeps these replicas:
  - `OrderEntity(id, userId)`, from `order/order/created`.
  - `OrderItemEntity(id, returnedWithId, sentWithId, orderId, compensatableAmount, productVariantVersionId)`, from `order/order/created` and `shipment/shipment/created`.
  - `ShipmentEntity(id, deliveredAt)`, from `shipment/shipment/created` and `shipment/shipment/status-updated`.
  - `ProductVariantVersionEntity(id, canBeReturnedForDays)`. **No event ever fills this table** (see §7). **FIXED (docs/fixes/return.md):** fed by `catalog/product-variant-version/created`.
- Publishes `return/return/created`, which shipment consumes to create the return shipment.
- **The return flow cannot succeed in the original.** The PVV handler has no `@Topic`/`@PostMapping`, so `ProductVariantVersionEntity` stays empty. Every `createReturn` that passes the earlier checks therefore fails with `Product variant version not found` (report 01 §4.5 item 2, §5.2). **FIXED (docs/fixes/return.md):** the topic is subscribed; a buyer can return a delivered item within the return period.

## 2. Runtime contract (must stay identical)

| Item | Value |
|---|---|
| HTTP port | 8080 |
| GraphQL | `POST /graphql` (gateway calls `localhost:3500/v1.0/invoke/return/method/graphql`), `GET /sdl` |
| Health | `GET /health` |
| Dapr app-id | `return` |
| Dapr endpoints served | `GET /dapr/subscribe`, `GET /dapr/config`, `GET /healthz` |
| Pub/sub component | `pubsub` |
| Env vars | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, `SPRING_FLYWAY_ENABLED` (optional), `OTEL_*` (`OTEL_SERVICE_NAME=return`), Dapr SDK `DAPR_*` defaults |
| DB | Postgres 16, DB `return`, Flyway `classpath:db/migration`, `baselineOnMigrate=true` |

## 3. GraphQL schema summary

Queries and mutation:
- `Query.returns(first, skip, orderBy: ReturnOrderInput): ReturnConnection!`
- `Query.return(id: UUID!): Return!`
- `Mutation.createReturn(input: CreateReturnInput!): Return!` where `CreateReturnInput` is `{orderItemIds: [UUID!]!, reason: String!}`.

Types:
- `type Return @key(id)`: `id`, `reason`, `refundedAmount: Int!` (the DB `BIGINT` is converted with `Math.toIntExact`: an amount beyond the `Int` range fails the field), `createdAt: DateTime!`, `order: Order!`, and `returnedItems(first, skip, orderBy: CommonOrderInput): OrderItemConnection!`.
- `type Order @key(id)`: `id` only.
- `type OrderItem @key(id)`: `id` and `returnedWith: Return`.
- `type User @key(id)`: `id` and `returns(first, skip, orderBy: ReturnOrderInput): ReturnConnection!`.
- `ReturnConnection` and `OrderItemConnection` are both `@shareable`.

Enums and inputs:
- `enum ReturnOrderField {ID}` and `enum CommonOrderField {ID}`.
- `input ReturnOrderInput`, described as "Direction to sort returns in".
- `input CommonOrderInput`, described as **"Discount order"**. This is a copy-paste description and must be kept.

Federation:
- The `_entities` union is `Order | OrderItem | Return | User`.
- There are **no** resolvers for `Return`. See §7. **FIXED:** `ReturnResolver` (data loader, no auth).

## 4. Resolvers and authorization

The auth helper is the same as in user (see `user.md` §4).

| Operation | Behaviour |
|---|---|
| `returns(...)` | No explicit check. `ReturnConnection` always does `JOIN OrderEntity ON ReturnEntity.orderId = OrderEntity.id`. Its `authorizedUserFilter()` does `authorizedUser!!`, so a missing header throws an NPE when `totalCount`, `nodes` or `hasNextPage` resolves (**FIXED:** `Unauthorized access` at `/returns`). A non-employee gets `OrderEntity.userId = caller.id` added. Employees get no filter. Default order is `ReturnEntity.id ASC`. |
| `return(id)` | `returnRepository.findById(id).awaitSingle()` throws NoSuchElement if the return is missing. Then `orderRepository.findById(orderId).awaitSingle()`. Only **after** these DB reads does it need the header (`Unauthorized access`). A non-employee must own the order: `require` fails with `IllegalArgumentException("You are not authorized to view this return")` (now: header first, `checkIsEmployee()`, see Deviations). |
| `Return.order` | Stub `Order(orderId)`. |
| `Return.returnedItems(...)` | `OrderItemConnection` with predicate `returnedWithId = id`, no join, **no auth**. `CommonOrder` orders by `OrderItemEntity.id` with the given direction (the `field` value is ignored). |
| `OrderItem.returnedWith` | Null if `returnedWithId` is null. Otherwise it uses `ReturnDataLoader`, which throws an NPE if the return is missing (the FK-free column makes this possible in theory). **FIXED:** per-key `… : null`. |
| `User.returns(...)` | `ReturnConnection` with predicate `OrderEntity.userId = id`, ANDed with the auth filter. A non-employee who asks for another user gets an **empty** connection, not an error. A missing header gives an NPE, as in `returns` (**FIXED:** `Unauthorized access`). |
| `_entities(Order)` | Stub `Order(UUID.fromString(id))` with no existence check. A null id gives an NPE. |
| `_entities(OrderItem)` | `FederatedTypeSuspendResolver`. A null id returns null. Otherwise `findById` runs, and if nothing is found it returns the stub `OrderItem(id, returnedWithId = null)`. No dataloader is used: one query per representation. |
| `_entities(User)` | Stub `User(id)`. |
| `_entities(Return)` | **No resolver is registered.** graphql-kotlin returns null for that representation plus an error ("Unable to resolve federated type"). Shipment's `Return.shipment` extension is the only path that would need it. **FIXED:** resolved through `ReturnDataLoader`, no auth (like `OrderItem.returnedWith`); an unknown id is a null entry + error. |
| `createReturn(input)` | See below. The original's `Mutation` class is `@Transactional(REQUIRES_NEW)` (here: the `@Transactional` service method `createReturn`, same scope), and `dfe.authorizedUser` is evaluated (header required) before the service call. |

`ReturnService.createReturn` steps. **The order and the error messages are part of the contract.**
**FIXED (docs/fixes/return.md):** steps 5 and 6 now run before step 2 (empty list → "At least one order item id must be
provided", unknown ids → "Order item ids [..] do not exist"); in step 9 a shipment that is not replicated counts as not
delivered. All messages unchanged.
1. `orderItems = orderItemRepository.findAllById(orderItemIds)`.
2. `orderItems.first().orderId`. An empty result (no ids, or none found) throws `NoSuchElementException("List is empty.")`. Because of this, the check in step 5 is **unreachable**.
3. `order = orderRepository.findById(orderId).awaitSingle()`.
4. `require(order.userId == caller.id)` fails with "Only the original buyer can create returns". Employees and admins are not exempt.
5. `require(orderItemIds.isNotEmpty())` fails with "At least one order item id must be provided" (dead code).
6. Missing ids fail with `"Order item ids $missingSet do not exist"`, where `missingSet` is a Kotlin `Set.toString()`, `[a, b]`.
7. Already returned items fail with `"Order item ids ${list of ids} have already been returned"`.
8. Every item needs `sentWithId`, `compensatableAmount` and `productVariantVersionId` to be non-null, otherwise it fails with "Missing data about order items, please try again later".
9. `shipmentRepository.findAllById(set of sentWithId)`. Every shipment *found* must have `deliveredAt` set, otherwise it fails with "At least one order item was not delivered". Missing shipments are silently ignored.
10. All items must share one `orderId`, otherwise it fails with "All order items must belong to the same order". This runs after the ownership check, which only looked at the first item's order.
11. `productVariantVersionRepository.findAllById(set of pvvIds)`. Then for each item, in order:
    - If the PVV is missing, fail with `IllegalStateException("Product variant version not found")`. **This always happens in the original.**
    - If `canBeReturnedForDays != null`:
      - `require(> 0)` fails with "Order item <id> cannot be returned".
      - `passedDays = Duration.between(deliveredAt, now).toDays()`. This throws an NPE if the shipment was missing in step 9.
      - `require(passedDays <= days)` fails with "Order item <id> can no longer be returned".
12. `refundedAmount = Σ compensatableAmount`. `save(ReturnEntity(reason, orderId, refundedAmount, createdAt = OffsetDateTime.now(), id = null))`. The DB generates the id.
13. Set `returnedWithId = saved.id` on every item, then `saveAll(orderItems)`. (The original also had DB triggers on `OrderItemEntity` for the same rules as steps 7-11; the port checks them in `ReturnService` only, §5.)
14. Publish `return/return/created` (§6) **inside** the still-open transaction. If the publish fails, everything rolls back. If the commit fails after the publish, the event has already gone out.
15. Return `saved.toDTO()`. `createdAt` is the in-memory `now()`, not a DB read-back.

## 5. Persistence

Migration: one `V1_1__create_relations.sql` in the common style (docs/PORTING.md §6): the original's tables plus the
port's lookup indexes (`OrderItemEntity(returnedWithId)`, `OrderEntity(userId)`, `ReturnEntity(orderId)`), `reason` as
`TEXT` (the original: `VARCHAR(255)`), **without the original's triggers** (below).

Tables:
- `OrderEntity(id PK, userId NOT NULL)`
- `ReturnEntity(id PK DEFAULT uuid_generate_v4(), reason TEXT, orderId FK→OrderEntity, refundedAmount BIGINT, createdAt TIMESTAMPTZ)`
- `OrderItemEntity(id PK, returnedWithId NULL, sentWithId NULL, orderId NOT NULL FK→OrderEntity, compensatableAmount BIGINT NULL, productVariantVersionId NULL)`
- `ProductVariantVersionEntity(id PK, canBeReturnedForDays INT NULL)`
- `ShipmentEntity(id PK, deliveredAt TIMESTAMPTZ NULL)`

Triggers of the original (dropped: `ReturnService` checks the same rules, each covered by a test: same order, delivered
shipment, return period):
- `before_update_orderitem_check_return_order_match` (BEFORE UPDATE, every row). If `returnedWithId` is set, the return's `orderId` must equal the item's `orderId`. It also fires on the `ON CONFLICT DO UPDATE` path of the upserts.
- `check_return_trigger` (BEFORE UPDATE **OF returnedWithId**). It fires on every full-row save of `OrderItemEntity`, even when the value is NULL.
  - It raises 'Shipment has not been delivered yet.' when there is no delivered shipment. This includes a missing PVV, because of the inner join.
  - It raises 'Return period of % days has been exceeded.' using `DATE_PART('day', createdAt - deliveredAt)`.

Writes, as native queries:
- `INSERT INTO OrderEntity (id, userId)`
- `INSERT INTO ShipmentEntity (id, deliveredAt)`
- `INSERT INTO ProductVariantVersionEntity (id, canBeReturnedForDays)` (never called)
- `upsertOrderItemFromShipment`: `INSERT (id, sentWithId, orderId) … ON CONFLICT (id) DO UPDATE SET sentWithId`
- `upsertOrderItemFromOrder`: `INSERT (id, compensatableAmount, orderId, productVariantVersionId) … ON CONFLICT (id) DO UPDATE SET compensatableAmount, productVariantVersionId`. `orderId` is not updated.

Writes through `save()`:
- the Return insert;
- the OrderItem full-row update;
- the Shipment `deliveredAt` update, which writes all columns.

Identifiers are unquoted, so Postgres lowercases them.

## 6. Events

Subscribed. Every handler is non-transactional and returns 200 with an empty body. **FIXED (docs/fixes/return.md):** every handler runs in one transaction and is idempotent (`ON CONFLICT`); see the fix notes below the table. A deserialization error returns 400, and an exception returns 500, which makes Dapr redeliver.

| Topic | Route | `data` fields used | Handler |
|---|---|---|---|
| `shipment/shipment/created` | `/subscription/shipment/shipment/created` | `id`, `orderId?`, `status` (enum `PENDING`, `IN_PROGRESS`, `DELIVERED`, `FAILED`; any other value gives 400), `orderItemIds`. `returnId`, `shipmentMethodId` and `shipmentAddressId` are ignored. | If `orderId == null`, return immediately (return shipments are ignored). Otherwise insert `ShipmentEntity(id, deliveredAt = now if DELIVERED else null)`, then call `upsertOrderItemFromShipment(itemId, shipmentId, orderId)` for each item. A missing `OrderEntity` violates the FK and returns 500. The shipment row is already written at that point, so **every redelivery then fails on the duplicate shipment id** (a permanent failure). |
| `shipment/shipment/status-updated` | `/subscription/shipment/shipment/status-updated` | `id`, `status`. `orderId`, `returnId` and `orderItemIds` are ignored. | Acts only when the status is `DELIVERED`: `findById(id).awaitSingle()`, then set `deliveredAt = now` and `save`. An unknown shipment returns 500; this always happens for **return shipments**, because they were never registered, so Dapr redelivers forever. |
| `order/order/created` | `/subscription/order/order/created` | `id`, `userId`, `orderItems[].{id, productVariantVersionId, compensatableAmount}`. The Rust order service sends many more fields (camelCase), and they are ignored. | `INSERT OrderEntity` (a duplicate returns 500), then `upsertOrderItemFromOrder` for each item. Not idempotent. |

Fixed behaviour of the handlers (docs/fixes/return.md):
- `shipment/created`: shipment row and item upserts in one transaction; the shipment insert is `ON CONFLICT DO UPDATE SET deliveredAt = COALESCE(existing, new)`. A missing order rolls everything back (500) and the redelivery succeeds once `order/created` arrived.
- `status-updated` (`DELIVERED`): with `orderId` → upsert `deliveredAt = now` (also if `shipment/created` was not processed yet); without `orderId` (return shipments) → `UPDATE … WHERE id` (no-op for unknown ids). Always 200. `orderId` is now read from the payload (shipment sends it, NON_NULL).
- `order/created`: `INSERT … ON CONFLICT (id) DO NOTHING`, so a duplicate is 200.

**Not subscribed:** `catalog/product-variant-version/created`. `EventController.onProductVariantVersionCreated` exists without `@Topic` or `@PostMapping`. It is missing from `/dapr/subscribe`, and a POST to `/subscription/catalog/product-variant-version/created` returns **404**. The service keeps the handler logic as dead code but exposes **no** route.
**FIXED (docs/fixes/return.md):** subscribed, route `/subscription/catalog/product-variant-version/created` (SDK convention), fields `id`, `canBeReturnedForDays?` (catalog omits it when null = returnable indefinitely), idempotent upsert, transactional.

`/dapr/subscribe` returns exactly the three entries above. Each has `pubsubName` `pubsub`, `route` `/subscription/<topic>` and `metadata` `{}`.
**FIXED:** four entries, the three of the original plus `{"pubsubName":"pubsub","topic":"catalog/product-variant-version/created","route":"/subscription/catalog/product-variant-version/created","metadata":{}}`.

Published: `return/return/created`, sent with the Dapr SDK `DefaultObjectSerializer` (NON_NULL):
```json
{"id":"<uuid>","orderId":"<uuid>","orderItemIds":["<uuid>",…],"reason":"…","refundedAmount":123,"createdAt":"2024-05-01T10:20:30.123456Z"}
```
- `orderItemIds` is the **input list as given**, including order and duplicates, not the de-duplicated entity list.
- `refundedAmount` is a JSON number (Long).
- `createdAt` is `ISO_OFFSET_DATE_TIME` of the in-memory `now()`: the JVM default zone (UTC in the image), µs precision, trailing zeros stripped.
- The docs say `orderItemsIds`. The code says `orderItemIds`, and the code is right.

There is no service invocation.

## 7. Quirks to preserve

1. The PVV replica is never fed, so `createReturn` always fails with "Product variant version not found" once the earlier checks pass. The return flow (T34) therefore never fires in the original. **FIXED (docs/fixes/return.md).**
2. An empty or entirely unknown `orderItemIds` raises `NoSuchElementException` ("List is empty.") instead of the intended validation message. **FIXED.**
3. The ownership check runs before the existence and same-order checks, and uses only the first item's order. Employees cannot create returns on behalf of a buyer. (Partly changed by fix 2: existence is now checked before ownership; the rest is kept, see docs/fixes/return.md.)
4. `returns` / `User.returns` without a header gives an NPE inside the connection fields. A non-employee querying `User.returns` of another user gets an empty result, not an error. **FIXED** (NPE → `Unauthorized access`; the empty result is kept).
5. `Query.return` reads the DB before checking authentication.
6. `_entities(Return)` has no resolver. **FIXED.**
7. `refundedAmount` is narrowed from Long to Int in GraphQL (an amount beyond the `Int` range fails the field instead of wrapping), but stays a Long in the event.
8. The event handlers are not idempotent and not transactional. Partial failures leave the handler permanently failing on redelivery (shipment created before order created; duplicate deliveries). Status updates for return shipments always fail. **FIXED.**
9. `deliveredAt` is the time the event was processed, not the provider's delivery time.
10. `Return.returnedItems` and `OrderItem.returnedWith` have no auth.
11. The `CommonOrderInput` description is "Discount order". The `field` in `CommonOrderInput` is ignored, and the order is always by id.

## 8. Parts needing non-trivial migration

- **ReturnConnection join.** The connection base class must support a join plus a predicate on the joined table, both for count and nodes. If the shared connection helper does not support joins, add that **additively** to `libs/common`, or implement it locally.
- **Native upserts with `ON CONFLICT`.** These must run unchanged. (The original's triggers are gone, §5.)
- **REQUIRES_NEW transaction around `createReturn`, with the publish inside it.**
- **Federation.** `OrderItem` uses a suspend resolver. `Return` must stay unresolved: do *not* add a resolver. (Superseded by the fixes: `Return` is resolved.)
- **A dead PVV handler with no route.** (Superseded by the fixes: the handler is subscribed.)

## 9. Test plan

Unit:
- `ReturnService` validation order and exact messages, with fake repositories: empty input, unknown ids, foreign buyer, already returned, missing data, undelivered, mixed orders, PVV missing (the default), `canBeReturnedForDays` 0 / exceeded / ok.
- `toEventDTO` shape.

Integration (Testcontainers Postgres):
- `ReturnSchemaTest`: generated ids, foreign keys, no triggers.
- Seed via events, then `createReturn`:
  - (a) Default (no PVV row) → error "Product variant version not found", no Return row, no publish.
  - (b) A PVV row inserted directly via SQL with `canBeReturnedForDays` null or 30 → success. Then check:
    - `refundedAmount` is the sum;
    - the items have `returnedWithId` set;
    - exactly one publish of `return/return/created` with exact JSON;
    - the GraphQL result is correct.
  - (c) A second return for the same items → "have already been returned".
  - (d) A foreign buyer → "Only the original buyer…".
  - (e) An empty list → "List is empty." error.
- `return(id)`:
  - the owner, an employee, and a foreign buyer (the "not authorized" error);
  - an unknown id returns an error.
- `returns`:
  - a buyer sees only their own returns, an employee sees all;
  - without a header the call fails;
  - paging and order.
- `User.returns` via `_entities(User)`: a foreign non-employee gets an empty result.
- `_entities(OrderItem)`:
  - a known id returns `returnedWith`;
  - an unknown id returns a stub with `returnedWith: null`.
- `_entities(Return)` → error.
- `Return.returnedItems` paging.

Events (fake Dapr endpoint):
- `order/order/created` with extra Rust fields creates the order and items.
- `shipment/shipment/created`:
  - with an orderId: the shipment is created and the items get `sentWithId`;
  - without an orderId: a no-op returning 200;
  - DELIVERED at creation: `deliveredAt` is set.
- `status-updated`:
  - DELIVERED sets `deliveredAt`;
  - a non-DELIVERED status is a no-op;
  - an unknown shipment gives non-2xx.
- A duplicate `order/order/created` gives non-2xx.
- `POST /subscription/catalog/product-variant-version/created` → 404.
- `GET /dapr/subscribe` contains exactly the three topics.

SDL: the diff against `schemas/return.graphql` is empty.

## 10. Status

The service is done in `services/return`.
- Package: ``org.misarch.`return` `` (PORTING.md convention; the original used `org.misarch.returns`, which is not visible from outside).
- SDL: `tools/sdl-diff.sh return` is empty against both the live SDL and `--schemas`.
- Tests: 18 green.
  - 10 integration tests, covering:
    - the defective default (`Product variant version not found`);
    - success with a PVV row inserted by SQL, including the exact event JSON;
    - every validation message;
    - publish failure followed by rollback;
    - replication events, including the permanent-failure cases and the 404 on the PVV route;
    - connections, federation (`_entities(Return)` returns an error), and authorization.
  - 1 SDL test.
  - 7 unit tests for the validation order.
- Image: `misarch-kotlin/return:dev` was smoke-tested against Postgres 16. Flyway ran, and `/health`, `/dapr/subscribe` (same order as the reference) and GraphQL all worked.

How the original was mapped:
- `ReturnConnection` is now `BaseConnection` with `join = "INNER JOIN OrderEntity o ON t.orderId = o.id"`.
  - The predicates `o.userId = ?` (in `User.returns`) and the authorization filter `o.userId = ?` are unchanged.
  - The original's `authorizedUser!!` was kept, so a request without a header still throws an NPE.
- `CommonOrder` comes from libs/common; its only field `ID` orders by id, as in the original.
- `OrderItemResolver` remains a `FederatedTypeSuspendResolver` with one `findById` per representation.
- The event handlers are still non-transactional.
- `createReturn` runs in one transaction (`@Transactional` service method), and the publish happens inside it.

## 11. Deviations

| Deviation | Reason | Effect |
|---|---|---|
| `return/return/created` is published via the Dapr HTTP API instead of the SDK's gRPC client | project-wide decision | The payload JSON is the same (tested). Only transport latency differs |
| JDK 25 / Boot 4.1 / graphql-kotlin 11 runtime | uniform stack | performance only |
| `BaseConnection` SQL instead of QueryDSL (`SELECT 1` probe for `hasNextPage`; no `LIMIT` when `first` is null) | QueryDSL/kapt dropped | Same results and the same round trips |
| Transactions are on the service methods; the single-statement handlers (`shipment/status-updated`, PVV created) run without one | one transaction rule for all services | One BEGIN/COMMIT less per such event; same result |
| `_entities(User/Order)` without `id` resolves to `null` instead of a NullPointerException | libs/common `ReferenceEntityResolver` | Only malformed representations |
| `return(id)` of a foreign return: `Unauthorized access: <id> is not an employee or admin` (`checkIsEmployee()`, like every other ownership check) instead of `You are not authorized to view this return`; without `Authorized-User` the answer is `Unauthorized access` also for an unknown id (the header is checked before the database read) | one authorization style for all services | Error text only |

Not deviated (deliberately): the missing PVV subscription, the missing `Return` entity resolver, the NPE without a header, the validation order and messages, `Int` narrowing of `refundedAmount`, and non-idempotent, non-transactional event handlers. (State `faithful`; all but the `Int` narrowing were fixed afterwards, see below.)

## Integration fixes (2026-10-13, docs/INTEGRATION.md §4.1)

Found by the whole-system differential run against the running original:
- F1: events are decoded with Jackson-2 semantics for primitives (`spring.jackson.deserialization.fail-on-null-for-primitives: false`): a missing/null primitive becomes 0/false instead of HTTP 400, like the original (Spring Boot 3.2).
- F2: all connections use `PagingValidation.POSITIVE_LIMIT` (`first: 0` → `Limit must be greater than 0.`, negative → `Offset must not be negative.`), verified on the running original.
- F3: a missing row throws `NoSuchElementException()` without message → `Exception while fetching data (/x) : null`, verified on the running original (was `… : No value received via onNext for awaitSingle`).

## Fixes (2026-10-13, docs/fixes/return.md)

Defects of the original fixed here (state `faithful` = commit 060498d): PVV subscription (quirk 1, the return
flow now works end to end), transactional idempotent handlers incl. acknowledged status updates of return shipments
(quirk 8), intended validation messages (quirk 2), `Unauthorized access` instead of the NPE (quirk 4), `Return` entity
resolver (quirk 6), per-key `ReturnDataLoader` failures, an unreplicated shipment counts as not delivered. The DB
triggers were checked and do not block the intended flow (no migration). SDL unchanged; `/dapr/subscribe` has one more
entry; `status-updated`'s `orderId` is now read. `tools/build.sh :services:return:test`: 18 green (10 integration incl.
the end-to-end return, 7 unit, 1 SDL).
