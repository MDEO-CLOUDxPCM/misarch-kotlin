# Service spec: `order`

Original: `/home/devbox/workspace/misarch/order` (Rust 2024 edition, axum 0.8, async-graphql 7.0.16,
mongodb driver 2.8, `mongodb-cursor-pagination` 0.3.2, `graphql_client` 0.14, reqwest 0.12, bson 2.14,
chrono 0.4.40). Crate name `misarch-order` (Rust ident `misarch_order`; this matters for error strings, see §11).
Reference SDL: `/home/devbox/workspace/misarch/schemas/order.graphql` (generated with
`--generate-schema`, `SDLExportOptions::new().federation()`); it matches the code.

The code is the truth. Where the docs (`docs/.../services/order.mdx`) disagree, this spec follows the code
(see §12).

§1-§8 describe the **original**; what `services/order` does differently (fixed defects, the Kotlin conventions) is
marked **FIXED** there and listed completely in §12 "Deviations". The implementation (§9, §10) is a Kotlin service
like the other services (docs/PORTING.md §2-§6).

## 1. Role in the system

- Owns `Order`, `OrderItem` (embedded in the order document) and `OrderCompensation`.
- Holds replicas of User (+ address ids), ProductVariant (+ current version price / tax rate id, visibility),
  TaxRate (+ current version), Coupon (id) and ShipmentMethod (id), all fed by events.
- `createOrder` is the only place in MiSArch with service-to-service synchronous calls: 4 serial Dapr
  invocations (shoppingcart, inventory, discount, shipment), each a GraphQL request.
- `placeOrder` starts the buy-process saga by publishing `order/order/created` (full order payload).
- Contributes `User.orders` to the federated `User`.

## 2. Runtime contract (must stay identical)

| Item | Value |
|---|---|
| HTTP port | 8080 (bind `0.0.0.0:8080`, hard-coded) |
| GraphQL | `POST /` (gateway `routing_url: http://localhost:3500/v1.0/invoke/order/method/`). `GET /` serves GraphiQL HTML (endpoint `/`). |
| Health | `GET /health` → `200`, empty body. Docker healthcheck `wget -qO - http://localhost:8080/health` |
| Dapr app-id | `order` (app-port `${EXPERIMENT_CONFIG_SIDECAR_PORT:-8080}`, app-protocol http, Dapr HTTP port 3500) |
| Dapr endpoints served by app | `GET /dapr/subscribe` and the 7 POST event routes of §7 (no `/dapr/config`, no `/healthz`) |
| Dapr endpoints called | `POST http://localhost:3500/v1.0/invoke/{shoppingcart/method/, inventory/method/graphql, discount/method/graphql, shipment/method/graphql}`; `POST http://localhost:3500/v1.0/publish/pubsub/{order/order/created, order/order-compensation/created}`. Host and port are hard-coded (`localhost:3500`). |
| Pub/sub component | `pubsub` |
| Env vars | `MONGODB_URI` (required, process panics when unset; DB `order-database`, app name `Order`) → replaced by the Postgres variables of docs/PORTING.md. `OTEL_EXPORTER_OTLP_ENDPOINT` (default `http://localhost:4318`; metrics are pushed to `<endpoint>/v1/metrics` every 5 s, service name `order`, HTTP server metrics only). |
| CLI | `--generate-schema` writes `./schemas/order.graphql` and exits (build-time tool only). |
| Auth input | Header `Authorized-User: {"id":"<uuid>","roles":["buyer"|"employee"|"admin",…]}` set by the gateway |

## 3. GraphQL schema (federation v2.5)

Schema header: `@link(url: "https://specs.apollo.dev/federation/v2.5", import: ["@shareable", "@key", "@inaccessible", "FieldSet"])`.
The SDL must equal `reference/sdl/order.graphql` (tools/sdl-diff.sh). Summary:

- Scalars: `UUID`, `DateTime`, `_Any`, `FieldSet`, `link__Import`.
- `Query.order(id: UUID!): Order!`, `Query.orderItem(id: UUID!): OrderItem!`, `Query._entities(representations: [_Any!]!): [_Entity]!`.
  (`_service` is served at runtime but omitted from the exported SDL, as async-graphql does.)
- `Mutation.createOrder(input: CreateOrderInput!): Order!`, `Mutation.placeOrder(input: PlaceOrderInput!): Order!`.
- `type Order @key(fields: "id")`: `id: UUID!`, `user: User!`, `createdAt: DateTime!`, `orderStatus: OrderStatus!`,
  `placedAt: DateTime`, `rejectionReason: RejectionReason`, `invoiceAddress: UserAddress!`,
  `compensatableOrderAmount: Int!`, `paymentInformationId: UUID!`,
  `orderItems(first: Int, skip: Int, orderBy: CommonOrderInput): OrderItemConnection!`.
  Not exposed: shipment address and VAT number (stored, sent in the event).
- `type OrderItem @key(fields: "id")`: `id`, `createdAt: DateTime!`, `productVariant: ProductVariant!`,
  `productVariantVersion: ProductVariantVersion!`, `taxRateVersion: TaxRateVersion!`,
  `shoppingCartItem: ShoppingCartItem!`, `count: Int!`, `compensatableAmount: Int!`,
  `shipmentMethod: ShipmentMethod!`, `discounts(first: Int, skip: Int, orderBy: CommonOrderInput): DiscountConnection!`.
- `type User @key(fields: "id")`: `id: UUID!`, `orders(first: Int, skip: Int, orderBy: OrderOrderInput): OrderConnection!`.
- Stubs `@key(fields: "id", resolvable: false)` with only `id: UUID!`: `Discount`, `ProductVariant`,
  `ProductVariantVersion`, `ShipmentMethod`, `ShoppingCartItem`, `TaxRateVersion`, `UserAddress`.
- Connections `@shareable` with `nodes`, `hasNextPage: Boolean!`, `totalCount: Int!`: `OrderConnection`,
  `OrderItemConnection`, `DiscountConnection`.
- Enums: `OrderStatus {PENDING, PLACED, REJECTED}`, `RejectionReason {INVALID_ORDER_DATA, INVENTORY_RESERVATION_FAILED}`,
  `OrderDirection {ASC, DESC}`, `OrderOrderField {ID, USER_ID, NAME, CREATED_AT, LAST_UPDATED_AT}`, `CommonOrderField {ID}`.
- Inputs: `CreateOrderInput {userId, orderItemInputs: [OrderItemInput!]!, shipmentAddressId, invoiceAddressId, paymentInformationId, vatNumber: String}`,
  `OrderItemInput {shoppingCartItemId, shipmentMethodId, couponIds: [UUID!]!}`,
  `PlaceOrderInput {id: UUID!, paymentAuthorization: PaymentAuthorizationInput}`, `PaymentAuthorizationInput {cvc: Int}`,
  `OrderOrderInput {direction, field}`, `CommonOrderInput {direction, field}`.
- `union _Entity = Discount | Order | OrderItem | ProductVariant | ProductVariantVersion | ShipmentMethod | ShoppingCartItem | TaxRateVersion | User | UserAddress`.

### 3.1 Scalar formats (observable)

| Scalar | Output | Input |
|---|---|---|
| `UUID` | lowercase hyphenated | string, parsed by `uuid::Uuid::parse_str`: also accepts simple (32 hex), braced and `urn:uuid:` forms, and a BSON extended-JSON binary object. |
| `DateTime` | chrono `to_rfc3339()` = `SecondsFormat::AutoSi` with `+00:00` (not `Z`). Values are stored with millisecond precision, so it is `2026-10-13T12:00:00.123+00:00`, or `2026-10-13T12:00:00+00:00` when millis = 0. | (no DateTime inputs) |
| `Int` (from Rust `u64`/`usize`/`u32`/`u16`) | JSON number (u64 values are not clamped to 32 bit) | Unsigned range check: negative → `Invalid number`; above the type max → `Only integers from 0 to <MAX> are accepted.` (`first`/`skip` of `orderItems`/`discounts`: usize; `User.orders(first)`: u32, `skip`: u64; `cvc`: u16 → max 65535). |

## 4. Authorization

Header parsing (`AuthorizedUserHeader::try_from`): the header must be valid JSON with `id: UUID` and
`roles: [Role]`, where Role ∈ `buyer | admin | employee` (lowercase, exact). Unknown fields are ignored.
Any failure (missing header, non-UTF-8, bad JSON, unknown role, missing `roles`) means "no header";
the request still executes (no HTTP error).

`authorize_user(ctx, id)`:
- no (valid) header → error `Authentication failed. Authorized-User header is not set or could not be parsed.`
- allowed if any role is `admin` or `employee`, or `header.id == id` (a buyer, or a user with an empty role list).
- otherwise error `Authentication failed for user of UUID: \`<header.id>\`. Operation not permitted.`

| Operation | Auth check (order of checks matters) |
|---|---|
| `order(id)` | load order (not found error first), then `authorize_user(order.user.id)` |
| `orderItem(id)` | always fails before auth (see §5.3) |
| `_entities` Order / OrderItem / User | none |
| `User.orders` | `authorize_user(user.id)` |
| `Order.orderItems`, `OrderItem.discounts` | none (in-memory) |
| `createOrder` | `authorize_user(input.userId)` first |
| `placeOrder` | load order (not found first), then `authorize_user(order.user.id)` |

## 5. Resolvers (exact semantics)

Error messages are the GraphQL `errors[].message`. `<T>` type names are Rust `std::any::type_name`
strings (§11). A failing non-null root field gives `data: null`.

### 5.1 `order(id)`
`orders` lookup by id. Missing → `misarch_order::graphql::model::order::Order with UUID: \`<id>\` not found.`
Then authorize against the order's user. Returns the order.

### 5.2 `_entities` (federation)
- `Order {id}` → order by id, no auth. Missing → same message as 5.1.
- `User {id}` → user replica by id (`users`), no auth. Missing → `misarch_order::graphql::model::user::User with UUID: \`<id>\` not found.`
- `OrderItem {id}` → always fails (§5.3).
- Stub types and unknown `__typename` → async-graphql error (no resolver).

### 5.3 `orderItem(id)` and `_entities OrderItem` — QUIRK: always fails (**FIXED**: resolved from `order_items`, `orderItem` authorized against the order's user)
The original reads the MongoDB collection `order_items`, which is never written (order items only exist
embedded in `orders.internal_order_items`). So the result is always the error
`misarch_order::graphql::model::order_item::OrderItem with UUID: \`<id>\` not found.`,
for every id, including ids of existing order items. FIXED: `orderItem(id)` and `_entities(OrderItem)` resolve the
item (`OrderItemEntity`); `orderItem` is authorized against the user of the item's order.

### 5.4 `Order.orderItems(first, skip, orderBy)` and `OrderItem.discounts(first, skip, orderBy)`
In memory over the order's items (or the item's discounts):
1. Sort by `id`. Direction `orderBy.direction` (default ASC). `orderBy.field` is ignored (only `ID` exists).
   UUID comparison is **byte-wise unsigned** (Rust `Uuid` Ord, same as Postgres `uuid` order), not
   `java.util.UUID.compareTo`.
2. `totalCount` = number of all items. `nodes` = skip `skip` (default 0), take `first` (default unlimited).
3. `hasNextPage = totalCount > nodes.size + skip`.

Implementation (§9): `OrderItemConnection` / `DiscountConnection` (`BaseConnection`, `PagingValidation.NON_NEGATIVE`)
over `OrderItemEntity` (`WHERE orderId`) / `OrderItemToDiscountEntity` (`WHERE orderItemId`); each field is one
statement, run only when selected: `nodes` = `ORDER BY id|discountId ASC|DESC [LIMIT first] OFFSET skip`,
`totalCount` = `COUNT`, `hasNextPage` = `LIMIT 1 OFFSET first + skip` probe (false without `first`). Same results as
1.-3. for all non-negative arguments (tested against the in-memory formula in `OrderRelationalLoadingTest`).

### 5.5 `User.orders(first, skip, orderBy)` (MongoDB + `mongodb-cursor-pagination` 0.3.2)
1. `authorize_user(user.id)`.
2. Filter: orders with `user.id = user.id`.
3. Sort: `orderBy` default `{direction: ASC, field: ID}`. A missing direction means ASC and a missing field means ID.
   Field to sort key: ID → `_id`, USER_ID → `user._id`, NAME → `name`, CREATED_AT → `created_at`,
   LAST_UPDATED_AT → `last_updated_at`. The pagination library appends `_id: -1` (**descending**) as tiebreak
   when the key is not `_id`. NAME and LAST_UPDATED_AT do not exist in the documents, and USER_ID is constant
   under the filter. So for USER_ID, NAME and LAST_UPDATED_AT the result is effectively **id DESC whatever
   the direction**. CREATED_AT sorts by `created_at <dir>, id DESC`. ID sorts by `id <dir>`.
   SQL: `ORDER BY <col> <dir>, id DESC` (ID: `ORDER BY id <dir>`).
4. Page size: `limit = (first ?: 25) + 1`. **The default page size is 25**, not unlimited.
5. `totalCount` = count of all orders of the user (no skip/limit). If 0: `{nodes: [], hasNextPage: false, totalCount: 0}`.
6. `skip == null || skip == 0`: fetch up to `limit`. `hasNextPage = fetched > first'` (first' = first ?: 25). If so, drop the last row.
7. `skip > 0`: fetch with OFFSET skip, up to `limit`. `hasNextPage = fetched + skip < totalCount`. The extra row is
   dropped only when `hasNextPage`. **QUIRK**: when `skip + first' + 1 == totalCount`, the page returns
   `first' + 1` nodes and `hasNextPage = false`.

Implementation: the quirk of 7. is FIXED (docs/fixes/order.md: `hasNextPage` = a further order exists, for every
`skip`). `OrderConnection` over the user's `OrderEntity` rows like 5.4: `nodes` = `ORDER BY <sort> LIMIT first'
OFFSET skip`, `totalCount` = `COUNT`, `hasNextPage` = probe at `first' + skip`, each only when selected. The sort is
the one of 3. (`OrderOrderInput.toOrderBy`, tie-break `id DESC`), the default page size 25 stays.

### 5.6 `createOrder(input)`
Steps run strictly in this order. The first failure aborts with the message shown. Nothing is persisted before step 13.

Input normalisation (Rust `BTreeSet<OrderItemInput>`, Ord = `shoppingCartItemId` only, Eq = all fields;
`couponIds` is a `HashSet`):
- `orderItemInputs` is stably sorted by `shoppingCartItemId` (byte order). Fully equal entries (same cart item,
  same shipment method, same coupon set ignoring order and duplicates) collapse to one. Entries with the same
  cart item but other differences are **all kept**.
- `couponIds` of each input are de-duplicated (set).

1. **Authorize** `input.userId` (§4).
2. **User replica**: `users` must contain `userId`, else `misarch_order::graphql::model::user::User with UUID: \`<userId>\` not found.`
3. **Shipment methods**: the list of `shipmentMethodId` of all inputs (sorted order, duplicates allowed) must all be
   in `shipment_methods`. Error for the **last** missing id in list order:
   `misarch_order::graphql::model::foreign_types::ShipmentMethod with UUID: \`<id>\` is not present in the system.`
4. **Coupons**: all `couponIds` of all inputs must be in `coupons`. Error for the last missing:
   `misarch_order::graphql::model::foreign_types::Coupon with UUID: \`<id>\` is not present in the system.`
   (Order within one input's set is unspecified in the original.)
5. **Addresses** (DEFECT, see §12.4): for shipment and then invoice address, only checks that the **user** exists.
   Error text, unreachable after step 2: `User address with UUID: \`<addressId>\` of user with UUID: \`<userId>\` not found.`
   **FIXED**: the address must be an address of the user (`user_addresses`, one lookup of both addresses; before
   2026-10-31 the user's `user_address_ids` array was read together with the user in step 2).
6. **Shopping cart** (S20, §8.1): read the user's cart lines `{cartItemId → (productVariantId, count)}` (count is cast with `as u64`).
   For each input in sorted order, its `shoppingCartItemId` must be in the cart, else (first missing)
   `` `(bson::uuid::Uuid, u64)` for product variant of UUID: `<shoppingCartItemId>` is not present in `std::collections::hash::map::HashMap<bson::uuid::Uuid, (bson::uuid::Uuid, u64)>`. `` (note the trailing space).
   Build `counts[productVariantId] = count` and `inputs[productVariantId] = input`. Iterate in sorted order and let
   the **last write win** when two inputs map to the same product variant. `pvIds` = keys of `counts`.
7. **Local product variants**: load the `product_variants` with id ∈ pvIds. Missing ones are **silently ignored** and ones
   with `is_publicly_visible = false` are **silently dropped** (QUIRK: such items vanish from the order, no error).
   `versions[pvId] = current version {id, price, taxRateId}` for the remaining ones.
8. **Inventory** (S21, §8.2) for **all** pvIds (also missing or invisible ones). Every pvId must be in the answer, else
   `` `u64` for product variant of UUID: `<pvId>` is not present in `std::collections::hash::map::HashMap<bson::uuid::Uuid, u64>`. ``
   Every `inventoryCount >= counts[pvId]`, else `Not all requested product variants are available.`
   This is a check only. Nothing is reserved.
9. **Tax rates**: load `tax_rates` for the taxRateIds of `versions`. Each version's tax rate must exist, else
   `` `misarch_order::graphql::model::foreign_types::TaxRate` for product variant of UUID: `<pvId>` is not present in `std::collections::hash::map::HashMap<bson::uuid::Uuid, misarch_order::graphql::model::foreign_types::TaxRate>`. ``
   `taxVersions[pvId] = taxRate.currentVersion`.
10. **Discounts** (S22, §8.3) for **all** pvIds (count and couponIds from `inputs`).
    `orderAmount = Σ versions.price` over the found and visible variants, **not multiplied by count** (QUIRK),
    u32 sum with wrap-around. Every pvId must be in the answer, else
    `` Product variant of UUID: `<pvId>` is not contained in the result which `findApplicableDiscounts` provides. ``
    Each pvId's discounts are collected into a set by discount id (duplicates collapse, the last one wins).
11. **Shipment fees** (S23, §8.4) for the found and visible variants only. The result is converted to u64 (a negative
    result gives the error `out of range integral type conversion attempted`) and then **discarded** (DEFECT §12.5).
12. **Build items**, one per found and visible variant (order unspecified):
    `id = random UUID v4`, `createdAt = now` (the same instant as the order, ms precision), product variant snapshot,
    product variant version, tax rate version, `shoppingCartItem.id` / `shipmentMethod.id` from the input,
    `count = counts[pvId]`, discounts = the set from step 10.
    `compensatableAmount = trunc_u64(price as f64 × Π discount.discount)`, multiplied in ascending discount-id byte order.
    `trunc_u64` is a saturating cast: NaN or negative → 0. QUIRK/DEFECT: it multiplies by `discount`, not
    `(1 − discount)`, ignores `count`, and ignores tax and shipment fees (§12.6).
13. **Order**: `id = random UUID v4`, `user = {id: userId}`, `createdAt = now`, `orderStatus = PENDING`, `placedAt = null`,
    `rejectionReason = null`, items, `shipmentAddressId`, `invoiceAddressId`,
    `compensatableOrderAmount = Σ item.compensatableAmount`, `paymentInformationId`, `vatNumber`.
    Insert. On failure: `Adding order failed in MongoDB.` Then re-read and return it.
    An order with **zero items** is possible: empty input list, or all variants missing or invisible.

The remote calls of steps 6, 8, 10 and 11 run **sequentially**, in this order, each awaited.

### 5.7 `placeOrder(input)`
1. Load the order (not found → the 5.1 message). Then authorize against `order.user.id`.
2. `paymentAuthorization = input.paymentAuthorization?.cvc?.let { CVC(it) }`. Both a missing input and a missing cvc give null.
3. Re-load the order. If `createdAt + 3600 s >= now`:
   - status `PENDING` → set `orderStatus = PLACED`, `placedAt = now`. On a DB error: ``Placing order of id: `<id>` failed in MongoDB.``
   - otherwise: `` `Placed` must be `OrderStatus::Pending` to be able to be placed. Order was already placed or rejected. ``
     (Rust Debug name: `Placed` or `Rejected`).
   Else (older than 1 h) → set `orderStatus = REJECTED` (**the status is changed whatever it was before, including
   PLACED**; `rejectionReason` stays null; `placedAt` is unchanged) and fail with
   ``Order of id: `<id>` was rejected as it is `OrderStatus::Pending` for too long.``
   This is a read-then-write without CAS: two concurrent placements can both succeed and both publish.
4. Re-load the order. Build the OrderDTO (§6.1). Publish `order/order/created` (§6.1). Only a transport error fails
   the mutation (after the status change has been committed). A non-2xx answer from Dapr is ignored.
5. Return the order. It is the instance re-loaded in step 4.

## 6. Events published

Publishing is a raw `POST http://localhost:3500/v1.0/publish/pubsub/<topic>` with `Content-Type: application/json`
and the DTO as body. Dapr wraps it in a CloudEvent. No metadata or headers.

### 6.1 `order/order/created` (on successful `placeOrder`)
```json
{
  "id": "uuid",
  "userId": "uuid",
  "createdAt": "2026-10-13T12:00:00.123Z",
  "orderStatus": "PLACED",
  "placedAt": "2026-10-13T12:00:05.456Z",
  "rejectionReason": null,
  "orderItems": [
    {
      "id": "uuid", "createdAt": "2026-10-13T12:00:00.123Z",
      "productVariantId": "uuid", "productVariantVersionId": "uuid", "taxRateVersionId": "uuid",
      "shoppingCartItemId": "uuid", "count": 1, "compensatableAmount": 20,
      "shipmentMethodId": "uuid", "discountIds": ["uuid"]
    }
  ],
  "shipmentAddressId": "uuid",
  "invoiceAddressId": "uuid",
  "compensatableOrderAmount": 20,
  "paymentInformationId": "uuid",
  "paymentAuthorization": {"cVC": 123},
  "vatNumber": "AB1234"
}
```
- Timestamps: chrono serde = RFC 3339 `AutoSi` **with `Z`** (unlike GraphQL's `+00:00`). Millisecond precision.
  The fraction is omitted when millis = 0 (= `Instant.truncatedTo(MILLIS).toString()`).
- `paymentAuthorization`: serde externally tagged enum with `rename_all = "camelCase"` applied to the variant `CVC`
  gives the key **`"cVC"`** (QUIRK; the docs say `cvc`). **FIXED**: `services/order` publishes `{"cvc": n}`. It is `null` when there is no cvc. `rejectionReason` is always null.
  `vatNumber` is null when absent. All keys are always present (no skipping of nulls).
- `discountIds` are in ascending byte order. The order of `orderItems` is unspecified (HashMap order in the original).
- Consumers: inventory, shoppingcart, return (and the order travels on to discount → payment, invoice).

### 6.2 `order/order-compensation/created` (from the unsubscribed compensation route, §7.8)
```json
{"id": "uuid", "amount_to_compensate": 123}
```
Keys are **snake_case** (no serde rename). The docs' `{id, orderId, orderItemIds, triggeredAt, amountToCompensate}` is wrong. **FIXED**: `services/order` publishes the documented payload (no subscriber in MiSArch).

## 7. Events consumed

`GET /dapr/subscribe` returns exactly this JSON, in this order (keys `pubsubName`, `topic`, `route`):

| # | topic | route |
|---|---|---|
| 1 | `catalog/product-variant/updated` | `/on-product-variant-updated-event` |
| 2 | `catalog/product-variant-version/created` | `/on-product-variant-version-creation-event` |
| 3 | `discount/coupon/created` | `/on-id-creation-event` |
| 4 | `tax/tax-rate-version/created` | `/on-tax-rate-version-creation-event` |
| 5 | `shipment/shipment-method/created` | `/on-id-creation-event` |
| 6 | `user/user/created` | `/on-id-creation-event` |
| 7 | `address/user-address/created` | `/on-user-address-creation-event` |
| 8 | `address/user-address/archived` | `/on-user-address-archived-event` |

The route `/on-shipment-creation-failed-event` exists but is **not** in the list (DEFECT §12.1).

Common handler contract (axum `Json<Event<T>>`, `Event = {topic: String, data: T}`; other CloudEvent fields are ignored):
- Content-Type must be `application/json` or `application/*+json` (Dapr sends `application/cloudevents+json`), else **415**.
- Syntactically invalid JSON → **400**. Valid JSON that does not fit `Event<T>` (missing or mistyped field) → **422**.
  These are axum rejection responses with a plain-text body.
- `topic` not equal to the expected topic(s) of the route → **500**, empty body.
- Handler/DB error → **500**, empty body (Dapr redelivers).
- Success → **200** `{"status":0}`. Dapr cannot parse the numeric status and treats the message as delivered.

### 7.1 `/on-id-creation-event` (data `{id: UUID}`)
By topic: `discount/coupon/created` → insert coupon `{id}`. `shipment/shipment-method/created` → insert shipment method `{id}`.
`user/user/created` → insert user `{id, userAddressIds: []}`. Plain insert: **a duplicate id → 500** (not idempotent,
a redelivery keeps failing). Any other topic → 500. **FIXED**: `ON CONFLICT DO NOTHING`, a duplicate → 200.

### 7.2 `/on-product-variant-version-creation-event` (topic `catalog/product-variant-version/created`)
Data `{id: UUID, retailPrice: u32, taxRateId: UUID, productVariantId: UUID}` (other fields ignored; retailPrice
negative or above u32 → 422). Look up the product variant `productVariantId`:
- exists → set `currentVersion = {id, price: retailPrice, taxRateId}`. Visibility is unchanged.
- missing → insert `{id: productVariantId, currentVersion, isPubliclyVisible: true}`.
There is no version-number check (the last processed event wins). Select then insert is not atomic, so a concurrent
duplicate insert gives 500 and a redelivery then takes the update path. A variant whose replica is "corrupt"
(`visibility_raw` set, §7.3) does not deserialize, so it counts as missing. The handler then tries to insert, which
fails as a duplicate → 500 on every delivery.

### 7.3 `/on-product-variant-updated-event` (topic `catalog/product-variant/updated`) — DEFECT: never succeeds with real payloads (**FIXED**: boolean `publiclyVisible` or `isPubliclyVisible` (bool or "true"/"false") sets `is_publicly_visible`)
Expected data `{id: UUID, isPubliclyVisible: String}`. Catalog publishes `{"id": "<uuid>", "publiclyVisible": <bool>}`.
Jackson's bean naming turns `isPubliclyVisible` into `publiclyVisible`; this was verified by the catalog service against
the original jar (docs/specs/catalog.md §9). serde therefore reports `missing field isPubliclyVisible`, so every real
event is answered **422**. Nothing changes, and Dapr keeps redelivering because a non-2xx answer other than 404 means
retry. Visibility changes never reach order: every replicated variant stays `is_publicly_visible = true`, so the
"drop invisible variants" path of `createOrder` is unreachable in practice. A boolean under the expected key
(`"isPubliclyVisible": false`) is also a 422 (`invalid type: boolean, expected a string`).
If the value were a string, the original would `$set` it as a string (no upsert). That would corrupt the replica:
later reads of that variant fail with a BSON deserialization error (`invalid type: string "…", expected a boolean`).
FIXED: catalog's `publiclyVisible` (and `isPubliclyVisible`) are read as a boolean (`ProductVariantDTO`).

### 7.4 `/on-tax-rate-version-creation-event` (topic `tax/tax-rate-version/created`)
Data `{id: UUID, rate: f64, version: u32, taxRateId: UUID}`. Upsert tax rate `taxRateId` with
`currentVersion = {id, rate, version}`. The last processed event wins.

### 7.5 `/on-user-address-creation-event` (topic `address/user-address/created`)
Data `{id: UUID, userId: UUID}`. `$push` id onto `users[userId].userAddressIds`. There is no upsert: an unknown user
→ no-op, 200. No dedup: a redelivery appends again. The list is never read by any resolver. **FIXED**: idempotent
append, upsert of the user (an address event before the user event is not lost); `createOrder` validates against it.

### 7.6 `/on-user-address-archived-event` (topic `address/user-address/archived`)
Data `{id: UUID, userId: UUID}`. `$pull` removes **all** occurrences of id. An unknown user → no-op, 200.

### 7.7 Topic routing
Each route also accepts only its own topic(s). For example, `user/user/created` posted to `/on-user-address-creation-event` → 500.

### 7.8 `/on-shipment-creation-failed-event` (topic `shipment/shipment/creation-failed`; reachable only by direct POST — **FIXED**: subscribed; `orderId` null/missing → 200 no-op; each item compensated once, redelivery → 200 without a second compensation)
Data `{orderId: UUID, orderItemIds: [UUID]}` (the shipment's `orderId` is nullable → null gives 422).
1. The order must exist, else 500.
2. `verify_items_uncompensated` — **inverted DEFECT**: Mongo query
   `order_item_ids: {$not: {$elemMatch: {$in: orderItemIds}}}`. If **any** existing compensation shares **no** item
   with `orderItemIds` (an empty-array compensation also counts), → 500. So the first compensation succeeds, a later
   one for unrelated items fails, and a repeated one for the same items succeeds again.
   SQL: `EXISTS (SELECT 1 FROM order_compensations WHERE NOT (order_item_ids && $ids))`.
3. `amount = Σ compensatableAmount` of the order's items whose id ∈ orderItemIds.
4. Insert `{id: v4, orderId, orderItemIds, triggeredAt: now, amountToCompensate}`.
5. Publish §6.2. A transport error → 500 (the row stays).

## 8. Service invocations made (all from `createOrder`)

All requests: `POST`, `Content-Type: application/json`, body = graphql_client `QueryBody`
`{"variables": {...}, "query": "<verbatim .graphql file content>", "operationName": "<Op>"}`.
Only the shoppingcart request carries a header beyond the defaults. HTTP status is not checked: the body is always
parsed as `{data?, errors?}`. GraphQL `errors` are ignored if `data` is present.
Error mapping: transport failure → `error sending request for url (<url>)`; body that does not decode into the
expected shape (including an unknown `__typename`) → `error decoding response body`; `data` absent or null →
the "…is empty." message of the call.

### 8.1 S20 shoppingcart — `POST http://localhost:3500/v1.0/invoke/shoppingcart/method/` (path `/`, not `/graphql`)
Header `Authorized-User` = the **caller's** header re-serialized as `{"id":"<uuid>","roles":["buyer",…]}` (only these two keys).
operationName `GetShoppingCartProductVariantIdsAndCounts`, variables `{"representations":[{"__typename":"User","id":"<userId>"}]}`.
Query file `queries/get_shopping_cart_product_variant_ids_and_counts.graphql`:
`_entities(representations) { __typename ... on User { shoppingcart { shoppingcartItems { nodes { id, productVariant { id }, count } } } } }`.
Uses `_entities[0]`. Null → `` Response data of `query_counts_by_product_variant_ids` query is empty. `` (also for `data` null).
An empty `_entities` list panics in the original (`Vec::remove(0)`), which drops the connection.

### 8.2 S21 inventory — `POST http://localhost:3500/v1.0/invoke/inventory/method/graphql`
operationName `GetUnreservedProductItemCounts`, variables `{"representations":[{"__typename":"ProductVariant","id":"<pvId>"},…]}`
(one per pvId, any order, also for an empty list). Query: `_entities { __typename ... on ProductVariant { id, inventoryCount } }`.
`data` null → `` Response data of `check_product_variant_availability` query is empty. ``; a null entity →
``Response data of `check_product_variant_availability` query could not be parsed, `maybe_product_variant_enum` is `None` ``;
a negative `inventoryCount` → `out of range integral type conversion attempted`.

### 8.3 S22 discount — `POST http://localhost:3500/v1.0/invoke/discount/method/graphql`
No auth header. operationName `GetDiscounts`, variables
`{"findApplicableDiscountsInput":{"orderAmount":<i64>,"productVariants":[{"count":<i64>,"couponIds":["uuid"],"productVariantId":"uuid"}],"userId":"uuid"}}`.
Query: `findApplicableDiscounts(input: $findApplicableDiscountsInput) { productVariantId, discounts { id, discount } }`.
`data` null → `` Response data of `query_discounts` query is empty. ``

### 8.4 S23 shipment — `POST http://localhost:3500/v1.0/invoke/shipment/method/graphql`
No auth header. operationName `GetShipmentFees`, variables
`{"calculateShipmentFeesInput":{"items":[{"productVariantVersionId":"uuid","quantity":<i64>,"shipmentMethodId":"uuid"}]}}`.
Query: `calculateShipmentFees(input: $calculateShipmentFeesInput)`. `data` null → `` Response data of `query_shipment_fees` query is empty. ``

The service serves no service invocation endpoints other than GraphQL at `/`.

## 9. Persistence

MongoDB DB `order-database`, collections: `orders` (items embedded), `order_items` (read only, never written),
`order_compensations`, `users`, `product_variants`, `tax_rates`, `coupons`, `shipment_methods`. No indexes besides `_id`.

Postgres (Flyway `V1__init.sql`, `V2__relational_children.sql`, `V4__kotlin_naming.sql`, `V5__compensation_order_index.sql`;
V3 is left free for the mechanisms branch's `V3__local_copies`). Tables and columns follow the Kotlin convention
(table = entity class, column = property name, unquoted); V4 renamed the former snake_case tables without touching
their rows. Timestamps are truncated to milliseconds on write.

| Table (entity) | Columns | From |
|---|---|---|
| `UserEntity` | `id` pk | `users` |
| `UserAddressEntity` (link, `UserAddressKey`) | `userId` fk, `id`, pk `(userId, id)` | `users.user_address_ids` (created = INSERT, archived = DELETE) |
| `CouponEntity`, `ShipmentMethodEntity` | `id` pk | `coupons`, `shipment_methods` |
| `ProductVariantEntity` | `id` pk, `currentVersionId`, `currentVersionPrice bigint`, `currentVersionTaxRateId`, `isPubliclyVisible` | `product_variants` |
| `TaxRateEntity` | `id` pk, `currentVersionId`, `currentVersionRate double precision`, `currentVersionVersion bigint` | `tax_rates` |
| `OrderEntity` | `id` pk (default `gen_random_uuid()`), `userId` (index), `createdAt`, `orderStatus` (enum name), `placedAt`, `rejectionReason`, `shipmentAddressId`, `invoiceAddressId`, `compensatableOrderAmount bigint`, `paymentInformationId`, `vatNumber` | `orders` |
| `OrderItemEntity` | `id` pk (default), `orderId` fk (index), `position`, `createdAt`, the product variant (version) and tax rate version snapshot, `shoppingCartItemId`, `count`, `compensatableAmount`, `shipmentMethodId` | `orders.internal_order_items[]` |
| `OrderItemToDiscountEntity` (link, `OrderItemToDiscountKey`) | `orderItemId` fk, `discountId`, `discount`, pk `(orderItemId, discountId)` | `internal_discounts` |
| `OrderCompensationEntity` | `id` pk (default), `orderId` (index, V5), `triggeredAt`, `amountToCompensate` | `order_compensations` |
| `OrderCompensationToOrderItemEntity` (link, `OrderCompensationToOrderItemKey`) | `orderCompensationId` fk, `orderItemId`, `position`, pk `(orderCompensationId, orderItemId)` | `order_compensations.order_item_ids` |

Code (docs/PORTING.md §2-§6): `@Table` entities, `CoroutineCrudRepository`s (derived finders; `@Query` for the
replica inserts/upserts, the compare-and-set status updates and the compensated items join), `ConditionQueries` with
`Condition.inArray` for every id list (one array parameter), `BaseConnection`s for the three connections,
`IdDataLoader`s for `order`/`_entities(Order|OrderItem)`.

Statements per operation:
- `order(id)` / `_entities(Order)`: the order row (data loader); `orderItem(id)`: the item row, then its order's row;
  `Order.orderItems`, `OrderItem.discounts`, `User.orders`: one statement per selected connection field.
- `createOrder` (`CheckoutService`): user exists, shipment methods (`= ANY`), coupons (`= ANY`, only if any), the two
  addresses of the user (`= ANY`), then S20, the product variants (`= ANY`), S21, the tax rates (`= ANY`), S22, S23;
  then one transaction (`OrderService.createOrder`): INSERT order, one INSERT per item, one per item discount. No
  transaction spans the service invocations.
- `placeOrder`: the order row (authorization, 1 h check), then one transaction (`OrderService.placeOrder`):
  compare-and-set `UPDATE ... WHERE orderStatus = 'PENDING'`, the items (`ORDER BY position`), their discounts
  (`= ANY`), publish. A failing publish rolls the status back. The rejection after 1 h is a single compare-and-set
  statement outside that transaction (it stays when the mutation fails).
- Compensation (`OrderCompensationService`, one transaction): order exists, compensated items of the order (join),
  the order's items, INSERT compensation + item rows, publish.

## 10. Configuration
Port 8080. Postgres env vars per docs/PORTING.md (`SPRING_R2DBC_*`, `SPRING_FLYWAY_URL`). Dapr endpoint
`misarch.dapr.http-endpoint` (default from `DAPR_HTTP_ENDPOINT`/`DAPR_HTTP_PORT`, `http://localhost:3500`). OTEL per
PORTING.md. GraphQL endpoint `POST /` (`graphql.endpoint: /`). Health `GET /health` (actuator).

## 11. Rust type-name strings in the original's messages
- `misarch_order::graphql::model::order::Order`, `misarch_order::graphql::model::order_item::OrderItem`,
  `misarch_order::graphql::model::user::User`, `misarch_order::graphql::model::foreign_types::{ShipmentMethod, Coupon, TaxRate}`
- `bson::uuid::Uuid`, `std::collections::hash::map::HashMap<K, V>`, `(bson::uuid::Uuid, u64)`, `u64`

## 12. Quirks and defects of the original (FIXED ones: docs/fixes/order.md)
1. **FIXED** (subscribed; return shipments ignored). `shipment/shipment/creation-failed` is not in `/dapr/subscribe`, so compensation never runs in a deployment. The route still works when it is called directly.
2. **FIXED** (item looked up in `order_items`). `orderItem(id)` and OrderItem entity resolution always fail (reads a never-written collection).
3. **FIXED** (boolean `publiclyVisible`/`isPubliclyVisible` replicated). `catalog/product-variant/updated` always fails with 422: catalog sends `publiclyVisible: bool`, order expects `isPubliclyVisible: String`. So visibility is never replicated (Dapr redelivers).
4. **FIXED** (address must be registered under the user). `validate_user_address` only checks that the user exists. Address ids and their owner are not checked.
5. **FIXED** (fees added to `compensatableOrderAmount`). `calculateShipmentFees` is called, but the result is discarded.
6. **FIXED** (`round(price × count × Π(1 − d))`, `orderAmount = Σ price × count`). `compensatableAmount = price × Π discount`: it uses the discount fraction itself, not `1 − d`, ignores `count`, and ignores tax and fees. The discount request's `orderAmount` = Σ unit prices without counts.
7. (not fixed, see docs/fixes/order.md) Invisible or unreplicated product variants are silently dropped from the order, and orders with 0 items are possible.
8. **FIXED** (only a PENDING order is rejected; compare-and-set). A placement after 1 h sets REJECTED even for an already PLACED order. The REJECTED status has no `rejectionReason`.
9. **FIXED** (each item is compensated once). `verify_items_uncompensated` uses inverted logic (§7.8).
10. **FIXED** (`{"cvc": n}`; documented compensation payload). `paymentAuthorization` is serialized as `{"cVC": n}`. The compensation event uses snake_case `amount_to_compensate`.
11. **FIXED** (off-by-one only; page size 25 and tie-break id DESC kept). `User.orders` with skip > 0 can return `first + 1` nodes (§5.5).
12. **FIXED** (idempotent, 200). Duplicate `user/user/created`, `discount/coupon/created` and `shipment/shipment-method/created` deliveries return 500.
13. Event handlers answer `{"status":0}` instead of `{"status":"SUCCESS"}` (now: 200 with an empty body).
14. Error messages expose Rust type names (§11) (now: Kotlin-style messages, see Deviations).
15. **FIXED** (CAS on PENDING). No CAS on placement (concurrent double placement possible). The status change is committed before the publish.

### Deviations (visible differences of `services/order` to the original)

Fixed defects (items 1-6, 8-12, 15 above; details in docs/fixes/order.md) plus:

| Area | Original | `services/order` |
|---|---|---|
| Persistence | MongoDB, items embedded | Postgres, §9 (env `SPRING_R2DBC_*`/`SPRING_FLYWAY_URL` instead of `MONGODB_URI`) |
| `/dapr/subscribe` | 8 entries; coupon, shipment method and user share `/on-id-creation-event` | generated by the Dapr SDK from the `@Topic` handlers: 9 topics (+ `shipment/shipment/creation-failed`), each on `/subscription/<topic>`, `"metadata":{}`; the old routes are gone. `GET /dapr/config`, `/healthz` exist |
| Event answers | axum: 415/400/422 for malformed bodies, 500 for a wrong topic or a failed handler, 200 `{"status":0}` | Spring: 400 for a body that does not decode (missing/invalid field), 415 for a wrong content type, 500 for a failed handler (Dapr redelivers), 200 with an empty body. There is no topic check (one route per topic) |
| Event decoding | serde: `retailPrice`/`version` u32, UUID forms of `uuid::parse_str`, `isPubliclyVisible` as string | Jackson: `Long`/`Double`, `UUID.fromString`, `publiclyVisible` or `isPubliclyVisible` boolean (a string `"true"`/`"false"` is coerced) |
| Health | `GET /health` 200 empty body | actuator, 200 `{"status":"UP"}` |
| GraphiQL / GET | `GET /` = GraphiQL HTML, batch requests rejected | graphql-kotlin defaults (`GET /` executes queries, `/sdl`) |
| Root field error | `{"data":null,"errors":[first error]}`, later mutation fields skipped | graphql-java: `data` with the failed root field omitted/null, all errors, every field executed |
| `_entities` | all-or-nothing, first error with path `["_entities"]`; unknown user → error | per representation: an unknown order/order item fails only its own entity; `User` resolves for any id (an unknown user has no orders) |
| Error texts | Rust type names, `… not found.`, `Authentication failed …`, `… failed in MongoDB.`, reqwest/graphql_client texts, async-graphql `Failed to parse "Int": …` | Kotlin style: missing row `Exception while fetching data (/x) : null`; validation `… : User with id <id> does not exist`, `Shipment method with id <id> does not exist`, `Coupon with id <id> does not exist`, `Address with id <id> is not an address of user <id>`, `Shopping cart item with id <id> is not in the shopping cart of user <id>`, `Not all requested product variants are available`, `Tax rate with id <id> of product variant <id> does not exist`, `The discount service returned no discounts for product variant <id>`, `The <app> service answered without data: <errors>`; placement `Order <id> is not pending: it was already placed or rejected`, `Order <id> was rejected as it was pending for more than one hour`; authorization `Unauthorized access` / `Unauthorized access: <id> is not an employee or admin`; paging `Limit must not be negative.` / `Offset must not be negative.` |
| `Authorized-User` | unparsable header or unknown role = no header | parsed by the common context factory: an unparsable header fails the request |
| Scalars | `UUID` accepts simple/braced/URN forms; `DateTime` `…123+00:00` | `UUID` = `UUID.fromString` forms; `DateTime` `…123Z` (extended-scalars, always 3 fraction digits) |
| `Int` range | `first`/`skip`/`cvc` unsigned with range errors, u64 outputs | 32-bit GraphQL `Int`; negative `first`/`skip` → paging error; `cvc` not range-checked |
| Item order | items sorted by `shoppingCartItemId` byte order (BTreeSet), full duplicates collapse | items in the order of `orderItemInputs`; for a product variant in several inputs the last input counts (as before); `position` and the event's `orderItems` follow that order |
| Missing variant in the inventory answer, negative counts | own error texts | treated as not available (`Not all requested product variants are available`) |
| Discount arithmetic | discounts multiplied in ascending id order | in the order discount returns them (rounding may differ by 1 unit with ≥ 2 discounts) |
| Service invocations | graphql_client body `{"variables","query","operationName"}`, status ignored, caller's header re-serialized | `DaprServiceInvoker.graphql`: body `{"query","variables"}` (same query files), non-2xx fails the mutation, `Authorized-User` forwarded to shoppingcart only |
| Publish | status of the sidecar ignored, PLACED committed before the publish | `EventPublisher`: a non-2xx answer fails `placeOrder` and rolls the placement back (the order stays PENDING); the compensation is rolled back likewise |
| Payloads | serde | byte-identical `order/order/created` (null fields written, ms timestamps with `Z`) except `paymentAuthorization` `{"cvc":n}` (fix); compensation payload as documented (fix) |
| Metrics | axum-otel-metrics | OpenTelemetry Java agent (`OTEL_*`) |

## 13. Tests

`services/order` (`tools/build.sh :services:order:test`, Testcontainers Postgres + `FakeDaprSidecar`):
- `OrderIntegrationTest`: createOrder with the four invocations (paths, bodies, forwarded header), stored rows,
  validation and invocation failures (nothing stored), dropped variants, empty orders; placeOrder with the exact
  `order/order/created` body, publish failure (stays PENDING), cvc absent, rejection after 1 h, concurrent
  placements (one succeeds); queries, authorization, entities, `User.orders` paging and ordering.
- `OrderEventIntegrationTest`: `/dapr/subscribe`, health, captured CloudEvents of the running services, replica
  events (idempotent), visibility, tax rates, addresses, compensation (each item once, publish failure rolls back).
- `OrderRelationalLoadingTest` (`SqlStatementLog`): statements per query, connection paging vs the in-memory formula,
  createOrder's replica reads with `= ANY` and its single write transaction.
- `OrderMigrationTest`: V1/V2 rows survive V2-V5. `EventPayloadTest`: payloads incl. the golden
  `reference/events/order_order_created.json`. `OrderAmountsTest`: amounts. `OrderSdlTest`: `tools/sdl-diff.sh order` empty.
