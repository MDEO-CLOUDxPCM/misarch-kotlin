# Port spec: `shoppingcart`

Original: `/home/devbox/workspace/misarch/shoppingcart` (Rust 2021, axum 0.8, async-graphql 7.0 with features
`bson, chrono, uuid, log`, mongodb driver 2.8.2, bson 2.x). The deployed image `ghcr.io/misarch/shoppingcart:main`
(built 2025-06-29) contains async-graphql 7.0.17, axum 0.8.4, bson 2.15.0, chrono 0.4.41, uuid 1.17.0.
Binary/crate name `misarch_shoppingcart` (this name leaks into error messages, see §6).
Reference SDL: `/home/devbox/workspace/misarch/schemas/shoppingcart.graphql`; it matches the code.

The code is the truth. Where `docs/.../services/shoppingcart.mdx` disagrees, this spec follows the code.
Since the defect fixes, items marked "FIXED (Cn)" describe the original; the port behaves as described in
`docs/fixes/shoppingcart.md`.

## 1. Role in the system

- Owns `ShoppingCart` (1 per user, embedded in the user document, no own id) and `ShoppingCartItem`.
- Keeps two local replicas fed by events: `users` (from `user/user/created`) and `product_variants`
  (from `catalog/product-variant/created`).
- Removes ordered cart items when `order/order/created` arrives.
- Publishes **no** events and makes **no** outbound calls (no Dapr client at all).
- Serves one **synchronous service invocation** from order (S20 in `01-misarch-architecture.md` §4.1):
  `order.createOrder` → `POST localhost:3500/v1.0/invoke/shoppingcart/method/` → app `POST /` with a GraphQL
  `_entities(User){shoppingcart{shoppingcartItems{nodes{id productVariant{id} count}}}}` query. This is on the
  buy critical path; the contract is in §5.
- Workload: every Gatling buy iteration runs `createShoppingcartItem` (gateway → shoppingcart) and `createOrder`
  (order → shoppingcart `_entities`). The frontend cart view queries `user{shoppingcart{…}}` (gateway → user +
  shoppingcart + catalog).

## 2. Runtime contract (must stay identical)

| Item | Value |
|---|---|
| HTTP port | 8080 (hard-coded, `0.0.0.0:8080`) |
| GraphQL | `POST /` (gateway `routing_url: http://localhost:3500/v1.0/invoke/shoppingcart/method/`; order invokes the same URL). `GET /` serves the GraphiQL IDE (HTML, endpoint `/`). No `/graphql` route. |
| Health | `GET /health` → `200`, empty body. Docker healthcheck `wget -qO - http://localhost:8080/health`. Does not check the DB. |
| Dapr app-id | `shoppingcart` (`--app-port ${EXPERIMENT_CONFIG_SIDECAR_PORT:-8080}`, `--app-protocol http`; the experiment-config-sidecar proxies to 8080) |
| Dapr endpoints served | `GET /dapr/subscribe`, `POST /on-topic-event`, `POST /on-order-creation-event` (no `/dapr/config`: Dapr gets 404, which is fine) |
| Pub/sub component | `pubsub` |
| Env vars (original) | `MONGODB_URI` (required, panics if unset), `OTEL_EXPORTER_OTLP_ENDPOINT` (default `http://localhost:4318`; metrics pushed to `<endpoint>/v1/metrics` every 5 s, OTLP/HTTP, resource `service.name=shoppingcart`) |
| DB (original) | MongoDB database `shoppingcart-database`, collections `users`, `product_variants`; app name `ShoppingCart` |
| CLI | `--generate-schema` writes `./schemas/shoppingcart.graphql` (federation SDL) and exits |
| Auth input | Header `Authorized-User: {"id":"<uuid>","roles":["buyer"|"admin"|"employee",…]}` set by the gateway (roles filtered to these three) and forwarded by order |
| Logging | `simple_logger` at WARN; async-graphql `Logger` extension (no functional effect) |

Port: env vars for Postgres follow `docs/PORTING.md` (replacing `MONGODB_URI`); `OTEL_EXPORTER_OTLP_ENDPOINT`
keeps its meaning. Port 8080, `/health`, `POST /`, the Dapr routes and the subscription JSON stay byte-identical.

## 3. GraphQL schema (federation v2.5, `@link(import: ["@shareable","@key","@inaccessible","FieldSet"])`)

Must produce an empty `tools/sdl-diff.sh` against `reference/sdl/shoppingcart.graphql`. Summary:

- `Query.shoppingcartItem(id: UUID!): ShoppingCartItem!`
- `Query._entities(representations: [_Any!]!): [_Entity]!`, `union _Entity = ProductVariant | ShoppingCartItem | User`
- `Mutation.updateShoppingcart(input: UpdateShoppingCartInput!): ShoppingCart!`
- `Mutation.createShoppingcartItem(input: CreateShoppingCartItemInput!): ShoppingCartItem!`
- `Mutation.updateShoppingcartItem(input: UpdateShoppingCartItemInput!): ShoppingCartItem!`
- `Mutation.deleteShoppingcartItem(id: UUID!): Boolean!`
- `type User @key(fields: "id") { id: UUID!, shoppingcart: ShoppingCart! }`
- `type ShoppingCart @shareable { lastUpdatedAt: DateTime!, shoppingcartItems(first: Int, skip: Int, orderBy: CommonOrderInput): ShoppingCartItemConnection! }`
- `type ShoppingCartItem @key(fields: "id") { id: UUID!, count: Int!, addedAt: DateTime!, productVariant: ProductVariant! }`
- `type ProductVariant @key(fields: "id", resolvable: false) { id: UUID! }`
- `type ShoppingCartItemConnection @shareable { nodes: [ShoppingCartItem!]!, hasNextPage: Boolean!, totalCount: Int! }`
- `input UpdateShoppingCartInput { id: UUID!, shoppingCartItems: [ShoppingCartItemInput!] }`
- `input ShoppingCartItemInput { count: Int!, productVariantId: UUID! }`
- `input CreateShoppingCartItemInput { id: UUID!, shoppingCartItem: ShoppingCartItemInput! }`
- `input UpdateShoppingCartItemInput { id: UUID!, count: Int! }`
- `input CommonOrderInput { direction: OrderDirection, field: CommonOrderField }`, `enum CommonOrderField { ID }`,
  `enum OrderDirection { ASC DESC }`
- Scalars `UUID`, `DateTime`, `_Any`, `FieldSet`, `link__Import`; `enum link__Purpose`. Descriptions (doc strings)
  are part of the SDL and must match the reference text exactly (e.g. "Count of items in basket.").
- `_service { sdl }` exists at runtime (async-graphql adds it when federation is enabled) but is not in the reference
  SDL; the gateway uses static schema files, so its exact text is irrelevant.
- `ShoppingCartOrderInput`/`ShoppingCartOrderField` exist in the Rust code but are unused and not in the schema.
- The live `_service { sdl }` of the original (`reference/sdl/shoppingcart.graphql`) has `type ShoppingCart`
  **without** `@shareable` (the Rust struct has no `shareable` attribute); the schemas-repo file
  (`reference/schemas/…`, what the gateway composes) shows `@shareable`. The port follows the code/live SDL.

### Scalars (exact wire format)

- `UUID` (bson::Uuid): input must be a JSON/GraphQL **string** accepted by Rust `uuid::Uuid::parse_str`:
  32 hex digits (simple), `8-4-4-4-12` hyphenated, `{hyphenated}` braced, or `urn:uuid:hyphenated`; hex is
  case-insensitive. Anything else is a coercion error. Output: lowercase hyphenated. (Java `UUID.fromString` is
  more lenient, e.g. accepts `1-2-3-4-5`; the port must use a strict parser.) The original additionally accepts an
  extended-JSON object `{"$binary":…}`; nobody sends that and the port does not reproduce it.
- `DateTime` (bson::DateTime, **millisecond** precision): output via chrono `to_rfc3339()` (`SecondsFormat::AutoSi`,
  no `Z`): `YYYY-MM-DDTHH:MM:SS+00:00` when the millisecond part is 0, else `YYYY-MM-DDTHH:MM:SS.mmm+00:00`
  (always exactly 3 digits). Not used as an input anywhere. Timestamps are taken with `DateTime::now()`, i.e.
  wall clock truncated to milliseconds; the port must truncate `Instant.now()` to millis before storing/returning.
- `Int` arguments backed by Rust unsigned types: `count: u32` (0..4294967295), `first`/`skip: usize`. Negative
  values are rejected while the arguments are parsed, i.e. **before** any authorization or lookup, as a field
  error (path = the field, `data: null` for root fields). Messages (async-graphql `InputValueError::propagate`
  appends the enclosing input type names):
  - `createShoppingcartItem`: `Failed to parse "Int": Invalid number (occurred while parsing "ShoppingCartItemInput") (occurred while parsing "CreateShoppingCartItemInput")` (verified)
  - `updateShoppingcart`: `Failed to parse "Int": Invalid number (occurred while parsing "ShoppingCartItemInput") (occurred while parsing "[ShoppingCartItemInput!]") (occurred while parsing "UpdateShoppingCartInput")`
  - `updateShoppingcartItem`: `Failed to parse "Int": Invalid number (occurred while parsing "UpdateShoppingCartItemInput")`
  - `shoppingcartItems(first|skip)`: `Failed to parse "Int": Invalid number`
  `count: 0` is accepted. Values above 2147483647 never reach the service through the gateway (GraphQL-JS
  validates `Int` as 32-bit).

### Pagination of `ShoppingCart.shoppingcartItems(first, skip, orderBy)` (in memory in the original)

1. Take all items of the cart (stored as a set: identical documents collapse, ids are unique anyway).
2. Sort by item id: ASC unless `orderBy.direction == DESC` (`orderBy.field` is ignored; only `ID` exists).
   Ordering is **unsigned byte order of the 16 UUID bytes** (= lexicographic order of the lowercase hex string).
   Java `UUID.compareTo` is signed and must NOT be used.
3. `totalCount` = number of items; `nodes` = `drop(skip ?: 0).take(first ?: ∞)`;
   `hasNextPage = totalCount > nodes.size + (skip ?: 0)`.
4. No arguments → all items (order relies on this).

Port (relational, docs/PORTING.md §6): the field resolver checks `first`/`skip` (negative → the original's parse
error) and returns a connection over the user's `ShoppingCartItemEntity` rows (`ConnectionQuery` of libs/common, the
SQL of the Kotlin services' `BaseConnection`); each field is one statement, run only when selected:
`nodes` = `SELECT t.* … WHERE t.userId = :u ORDER BY id ASC|DESC [LIMIT first] OFFSET skip`, `totalCount` =
`SELECT COUNT(t.id) …`, `hasNextPage` = `SELECT 1 … LIMIT 1 OFFSET first + skip` (no statement, `false`, without
`first`). Equivalent to steps 1-3: `totalCount > nodes.size + skip` ⇔ a row exists at offset `first + skip`
(`first` null: never). Postgres `uuid` order is the unsigned byte order.

## 4. Resolvers and authorization (exact semantics)

### 4.1 Authorized-User handling

- The GraphQL handler tries to parse the `Authorized-User` header (first value; must be visible ASCII) as JSON
  `{"id": <uuid string, formats as UUID scalar>, "roles": [<"buyer"|"admin"|"employee">…]}` with serde: both fields
  required, unknown fields ignored, role strings case-sensitive. **Any** failure (missing header, bad JSON, missing
  field, unknown role such as `"BUYER"`, bad uuid) silently means "no header"; the request is still executed.
- `authorize_user(ctx, id)`:
  - no header → error `Authentication failed. Authorized-User header is not set or could not be parsed.`
  - permitted iff `roles` contains `admin` or `employee`, or `header.id == id`.
  - otherwise → error ``Authentication failed for user of UUID: `<header.id>`. Operation not permitted.``
    (the **caller's** id, hyphenated lowercase, in backticks).
- Errors are ordinary GraphQL field errors (HTTP 200). All root fields are non-null, so a failing root field makes
  `data` null.

### 4.2 Operations (order of checks matters: the first failing step decides the error)

| Operation | Behaviour |
|---|---|
| `_entities(User)` | **No authorization.** Loads user by id from `users`. Missing → ``misarch_shoppingcart::graphql::model::user::User with UUID: `<id>` not found.`` Returns `User{id, shoppingcart}`. |
| `_entities(ShoppingCartItem)` | **No authorization.** Finds the item by id in any cart. Missing → ``ShoppingCartItem of UUID: `<id>` not found.`` |
| `_entities` generic | Representation not an object or unknown `__typename` (incl. `ProductVariant`, which is unresolvable) or missing/unparsable `id` → `Entity not found.`; `__typename` missing or not a string → `"__typename" must be an existing string.` All representations are resolved concurrently (`try_join_all`): if **any** fails, the whole `_entities` field fails (error path `["_entities"]`) and `data` is null. |
| `shoppingcartItem(id)` | 1. find item (missing → ``ShoppingCartItem of UUID: `<id>` not found.``) 2. `authorize_user(owner id)` 3. return item. (Unauthenticated callers learn whether an id exists.) |
| `updateShoppingcart(input{id, shoppingCartItems})` | 1. `authorize_user(input.id)`. 2. If `shoppingCartItems` is **non-null**: (a) dedupe the list as a set of `(count, productVariantId)` pairs (exact duplicates collapse; same variant with different counts stays twice); (b) **product-variant validation quirk** (§7 Q2): only the variant of the *last* pair in a random iteration order is checked against `product_variants`; failure → ``Product variant with the UUID: `<pvId>` is not present in the system.`` Empty list passes. FIXED (C2): all variants are checked (one `ANY` lookup); the error names the first unknown one in input order. (c) user must exist in `users` → else ``misarch_shoppingcart::graphql::model::user::User with UUID: `<id>` not found.`` (d) replace **all** items of the cart by new items (new random v4 id each, given count, `addedAt = now`, same `now` for all) and set `lastUpdatedAt = now` (one atomic update). 3. If `shoppingCartItems` is **null**: no validation, no user check, no write. 4. Load the cart; missing user → ``ShoppingCart with UUID: `<id>` not found.`` 5. Return `ShoppingCart`. |
| `createShoppingcartItem(input{id, shoppingCartItem{count, productVariantId}})` | 1. `authorize_user(input.id)` 2. user must exist → ``misarch_shoppingcart::graphql::model::user::User with UUID: `<id>` not found.`` 3. variant must exist in `product_variants` → ``Product variant with the UUID: `<pvId>` is not present in the system.`` 4. Intended "return existing item for this variant" lookup is **broken and always misses** (§7 Q1). 5. Always appends a new item `{id: random v4, count, addedAt: now, productVariant}` to the cart. `lastUpdatedAt` is **not** changed. 6. Returns the item built in memory (not re-read). FIXED (C1, C4): in one transaction (user row locked) the user's first item (by `addedAt`, id) for the variant is returned unchanged if it exists; otherwise the new item is inserted and `lastUpdatedAt = addedAt`. |
| `updateShoppingcartItem(input{id, count})` | 1. find item (missing → ``ShoppingCartItem of UUID: `<id>` not found.``) 2. `authorize_user(owner id)` 3. set the item's `count` (any u32, incl. 0) 4. re-read and return the item. `lastUpdatedAt` not changed (FIXED (C4): set to now). |
| `deleteShoppingcartItem(id)` | 1. find item (missing → ``ShoppingCartItem of UUID: `<id>` not found.``) 2. `authorize_user(owner id)` 3. remove it 4. return `true`. `lastUpdatedAt` not changed (FIXED (C4): set to now). |
| `User.shoppingcart`, `ShoppingCart.*`, `ShoppingCartItem.*` | Plain field access, no auth. `ShoppingCartItem.productVariant` returns the stub `{id}`. |

DB-failure messages (only on database errors, keep the literal text including "MongoDB"):
``Updating product_variant_ids of shoppingcart of id: `<userId>` failed in MongoDB.``,
``Add shoppingcart item of id: `<newItemId>` failed in MongoDB.``,
``Updating count of shoppingcart item of id: `<id>` failed in MongoDB.``,
``Deleting shoppingcart item of id: `<id>` failed in MongoDB.``,
`Product variants with the specified UUIDs are not present in the system.` (failure of the `$in` lookup);
unreachable: `Projection failed, shoppingcart item could not be extracted from user.`

## 5. Service invocation served (order → shoppingcart, S20)

Order (`order/src/graphql/mutation.rs` `query_counts_by_product_variant_ids`) sends:

```
POST http://localhost:3500/v1.0/invoke/shoppingcart/method/      (Dapr → app: POST /)
Content-Type: application/json
Authorized-User: <order's own incoming header, re-serialized: {"id":"…","roles":["buyer"]}>

{"variables":{"representations":[{"__typename":"User","id":"<userId>"}]},
 "query":"query GetShoppingCartProductVariantIdsAndCounts($representations: [_Any!]!) { _entities(representations: $representations) { __typename ... on User { shoppingcart { shoppingcartItems { nodes { id, productVariant { id }, count } } } } } }",
 "operationName":"GetShoppingCartProductVariantIdsAndCounts"}
```

Response (HTTP 200, `application/json`), e.g.

```json
{"data":{"_entities":[{"__typename":"User","shoppingcart":{"shoppingcartItems":{"nodes":[
  {"id":"<itemId>","productVariant":{"id":"<pvId>"},"count":1}]}}}]}}
```

- No pagination arguments → **all** items of the cart (sorted by id ASC). Order matches its `orderItemInputs`
  against these ids.
- Unknown user → `{"data":null,"errors":[{"message":"misarch_shoppingcart::graphql::model::user::User with UUID: `…` not found.","locations":[…],"path":["_entities"]}]}`;
  order then fails with "Response data … is empty".
- The header is irrelevant here (`_entities(User)` does not authorize) but must not break the request when
  present, absent or malformed.

## 6. Persistence

### Original (MongoDB `shoppingcart-database`)

- `product_variants`: `{_id: UUID(binary subtype 4)}`.
- `users`: `{_id: UUID, shoppingcart: {last_updated_at: Date(ms), internal_shoppingcart_items: [{_id: UUID, count: int, added_at: Date, product_variant: {_id: UUID}}…]}}`.
  The cart is embedded in the user document; every cart read loads the whole document.
- No indexes besides `_id`. Item lookups use `{"shoppingcart.internal_shoppingcart_items": {$elemMatch: {_id}}}`
  with positional projection (collection scan).

### Port (Postgres, Flyway `V1__init.sql`, naming per PORTING.md: table = entity name, unquoted)

```sql
CREATE TABLE ProductVariantEntity (id UUID PRIMARY KEY);
CREATE TABLE UserEntity (
    id                        UUID PRIMARY KEY,
    shoppingcartLastUpdatedAt TIMESTAMPTZ NOT NULL        -- embedded cart flattened; ms precision values
);
CREATE TABLE ShoppingCartItemEntity (                     -- embedded array -> child table
    id               UUID PRIMARY KEY,
    userId           UUID        NOT NULL REFERENCES UserEntity (id),
    count            BIGINT      NOT NULL,                -- Rust u32
    addedAt          TIMESTAMPTZ NOT NULL,
    productVariantId UUID        NOT NULL                 -- NO FK: the validation quirk lets unknown ids in
);
CREATE INDEX ShoppingCartItemEntity_userId_idx ON ShoppingCartItemEntity (userId);
```

No position column: the array order is not observable (every read sorts by item id). Mapping of the Mongo
operations (`persistence/ShoppingcartRepository.kt`, plain SQL via `DatabaseClient`):
- user-created event: `INSERT INTO UserEntity` with `now` (ms); PK violation → HTTP 500 (as Mongo's E11000).
  FIXED (C3): `ON CONFLICT (id) DO NOTHING` → 200, no change.
- variant-created event: `INSERT INTO ProductVariantEntity`; PK violation → 500. FIXED (C3): idem.
- order-created event: `DELETE FROM ShoppingCartItemEntity WHERE userId = :userId AND id = ANY(:ids)` (Mongo
  `$pull` scoped to that user's document; unknown user or ids → no-op, success). FIXED (C4): if rows were deleted,
  `lastUpdatedAt = now` (same transaction).
- `updateShoppingcart` (non-null list): one transaction (`@Transactional`, like the original's single-document
  `$set`): `DELETE … WHERE userId`, `INSERT` the new items, `UPDATE UserEntity SET shoppingcartLastUpdatedAt`.
- `createShoppingcartItem`: `INSERT` (the original's `$push`). FIXED (C1): `SELECT … FOR UPDATE` of the user row,
  lookup by `(userId, productVariantId)`, `INSERT` + `UPDATE UserEntity` only if missing.
- `updateShoppingcartItem` / `deleteShoppingcartItem` / item lookups (`$elemMatch` + positional projection, a
  collection scan in the original): by item `id` (primary key).
- `_entities(User)` and the result of `updateShoppingcart` (`find_one` of the user document): the `UserEntity` row
  only (id, `shoppingcartLastUpdatedAt`); the items are read by `shoppingcartItems` when (and as far as) selected.
- Existence checks (`validate_user`, `validate_shopping_cart_item`): `SELECT 1 … WHERE id = :id`.

## 7. Quirks and defects (reproduced by the faithful port; FIXED ones: docs/fixes/shoppingcart.md)

- **Q1 – createShoppingcartItem never reuses an existing item.** The lookup
  `query_shoppingcart_item_user_by_product_variant_id_and_user_id` projects
  `{"shoppingcart.internal_shoppingcart_items.$": 1, "_id": 0}`; the projected document lacks `_id` and
  `shoppingcart.last_updated_at`, so deserializing it into `User` always fails, the error is mapped to "not found",
  and the mutation always appends a **new** item. Adding the same variant twice yields two cart items. Carts of
  Gatling users grow until `order/order/created` removes the ordered items (aborted sessions leave items behind;
  the calibration's `maintain.py` cleans them). FIXED (C1).
- **Q2 – multi-item validation checks only one item.** `validate_shopping_cart_items` folds over the ids but
  ignores the accumulator, so only the result for the last element of the `HashSet` iteration order (random per
  request, Rust `RandomState`) counts. With k unknown variants among n distinct pairs, the mutation fails with
  probability ≈ k/n, and unknown variant ids can be stored. Port: dedupe pairs, shuffle with an injectable
  `Random` (default: unseeded), check only the last pair's variant. FIXED (C2).
- **Q3 – updateShoppingcart validates variants before checking that the user exists**, and with
  `shoppingCartItems: null` it skips both checks (only the final load can fail with "ShoppingCart … not found").
- **Q4 – `lastUpdatedAt` only changes on `updateShoppingcart` with a non-null list** (and on user creation). Item
  create/update/delete and the order event leave it unchanged. FIXED (C4).
- **Q5 – no count validation**: `count: 0` is stored; items with count 0 stay in the cart.
- **Q6 – `_entities(User)` and `_entities(ShoppingCartItem)` are unauthenticated**; anyone reaching the subgraph can
  read any cart. `shoppingcartItem` checks existence before authorization.
- **Q7 – error messages leak Rust type paths** (`misarch_shoppingcart::graphql::model::user::User …`). Reproduce
  verbatim.
- **Q8 – events are not idempotent**: a redelivered `user/user/created` or `catalog/product-variant/created`
  (e.g. the duplicate Keycloak/Ceres user publish) hits the unique `_id` and returns HTTP 500, so Dapr redelivers
  it forever (calibration acknowledges these with a reaper). `order/order/created` is idempotent. FIXED (C3).
- **Q9 – unknown topic on a route → HTTP 500** (both routes check `event.topic`).
- **Q10 – DB error messages mention MongoDB.** Keep the text.
- **Q11 – `_entities` is all-or-nothing** (`try_join_all`).

## 8. Events

### Published

None.

### Consumed (`GET /dapr/subscribe`, byte-identical body)

```json
[{"pubsubName":"pubsub","topic":"user/user/created","route":"/on-topic-event"},{"pubsubName":"pubsub","topic":"catalog/product-variant/created","route":"/on-topic-event"},{"pubsubName":"pubsub","topic":"order/order/created","route":"/on-order-creation-event"}]
```

Request bodies are Dapr CloudEvents (`Content-Type: application/cloudevents+json`); only `topic` (string) and
`data` are read; other envelope fields are ignored.

| Route | Topic | `data` fields read | Handler |
|---|---|---|---|
| `POST /on-topic-event` | `user/user/created` (publisher: user; payload = UserDTO) | `id: UUID` | insert user with empty cart, `lastUpdatedAt = now(ms)` |
| `POST /on-topic-event` | `catalog/product-variant/created` (publisher: catalog; ProductVariantDTO) | `id: UUID` | insert product variant id |
| `POST /on-order-creation-event` | `order/order/created` (publisher: order `placeOrder`; full OrderDTO, camelCase) | `id: UUID`, `userId: UUID`, `orderItems: [{shoppingCartItemId: UUID, count: u64}]` | delete the listed cart items from that user's cart |

Responses:
- success → `200`, `Content-Type: application/json`, body `{"status":0}` (Dapr cannot parse the numeric status,
  skips the check and treats 2xx as SUCCESS).
- topic not handled by the route → `500`, empty body (Dapr: RETRY).
- DB error (incl. duplicate insert) → `500`, empty body (RETRY). FIXED (C3): a duplicate insert is a no-op → 200.
- body rejected by axum's `Json` extractor: content type not `application/json` or `application/*+json` → `415`;
  malformed JSON → `400`; missing/ill-typed field (no `topic`, no `data.id`, bad uuid, negative `count`, missing
  `shoppingCartItemId`) → `422`. Bodies are plain-text serde messages (port: same status codes, text may differ).
  Dapr retries all of these except 404.

## 9. Service invocations made

None.

## 10. Deviations (final)

Everything not listed here is reproduced, including the engine semantics of async-graphql that matter for clients
(async-graphql layer `org.misarch.common.rust.asyncgraphql` in libs/common, formerly the `compat/` package; see §13).

- **D1** PostgreSQL instead of MongoDB (project decision); env vars `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`,
  `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD` replace `MONGODB_URI`; DB-error texts keep "MongoDB".
  Performance: relational access like the Kotlin services (docs/PORTING.md §6): a user/cart read is one
  `UserEntity` row, the items are 1-3 statements of the `shoppingcartItems` connection (page, count, probe; only the
  selected ones) instead of one document read; order's S20 query (`nodes` only) = user row + one item page. Item
  lookups by id use the primary key instead of a collection scan (cheaper than the original for large `users`
  collections). The statements of one query are not one snapshot (the document read was); a concurrent write
  between them can make `totalCount` and `nodes` disagree, as in the Kotlin services.
- **D2** `count`, `first`, `skip` are 32-bit GraphQL `Int` in graphql-java: values 2147483648..4294967295 (accepted
  by async-graphql for `u32`/`usize`) are rejected. Through the gateway they never arrive (GraphQL-JS validates
  `Int` as 32-bit). Negative values give the original's message, location and path (reproduced).
- **D3** Engine texts: syntax/validation errors (original e.g. `Unknown field "foo" on type "Query".`) and invalid
  `UUID` input (original: field error `Failed to parse "UUID": <uuid-crate message> …` with `data: null`; port:
  graphql-java request-level error) differ in wording. Shape is aligned (`data: null`, no `extensions`). Batched
  requests: `400` with an empty body (original: body `UnsupportedBatch`).
- **D4** Event handler 4xx bodies are not serde's texts; status codes are identical (415/400/422/500).
- **D5** Unknown paths answer Spring's 404 JSON (original: empty 404); `GET /dapr/config` and `GET /healthz` exist
  (Dapr SDK controller), the original answered 404.
- **D6** Telemetry: OpenTelemetry Java agent (original: axum-otel-metrics pushing OTLP metrics every 5 s).
- **D7** `_entities` representations are resolved sequentially; the original resolved them concurrently and
  reported the first error *to complete* (the port: the first by position). Only observable with several failing
  representations in one request.

## 11. Verified against the running original (2026-10-13)

`ghcr.io/misarch/shoppingcart:main` + `mongo:6.0` were started in isolation (outside a measurement window) and a
72-step scenario was replayed (`services/shoppingcart/difftest/`). It confirmed every rule above, notably Q1 (a
second `createShoppingcartItem` for the same variant creates a second item), the set semantics of
`updateShoppingcart` (`[2×P1, 2×P1, 3×P1, 1×P2]` → 3 items), unsigned id order, `first: 0` → `[]` +
`hasNextPage: true`, idempotent order events, 500 for duplicates/unknown topics, 422/415/400 for bad bodies.
Wire details observed:

- Error object shape: `{"message", "locations":[{"line","column"}], "path":[…]}`, no `extensions`; `data: null`
  whenever a root field fails (all root fields are non-null).
- `_entities` errors raised by the entity resolver carry `"path":["_entities"]`; `Entity not found.` and
  `"__typename" must be an existing string.` carry **no** `path` key.
- Two failing root fields in one request → only the **first** error is reported (async-graphql stops at the
  first non-null failure).
- Variable coercion errors are field errors with `data: null` and the field path, e.g.
  `Failed to parse "Int": Invalid number (occurred while parsing "ShoppingCartItemInput") (occurred while parsing "CreateShoppingCartItemInput")`
  and `Failed to parse "UUID": invalid group length in group 0: expected 8, found 1 (occurred while parsing "CreateShoppingCartItemInput")`.
  For negative `Int`s the port reproduces message, location (the argument value: `$i` / the literal) and path;
  invalid UUIDs are graphql-java request-level errors (D3).
- Errors are located at the **argument value** for argument parse errors, at the field otherwise.
- Errors inside an `_entities` element (e.g. `shoppingcartItems(first: -1)`) make `data` null; path
  `["_entities","shoppingcart","shoppingcartItems"]` (no list index).
- `GET /health`: `200`, `content-length: 0`; `GET /`: GraphiQL HTML (`text/html; charset=utf-8`);
  GraphQL responses: `Content-Type: application/graphql-response+json` (async-graphql-axum 7.0.17, whatever the
  `Accept` header); event success: `application/json` `{"status":0}`; 422 bodies are `text/plain; charset=utf-8`.
- `addedAt`/`lastUpdatedAt` render as `…T…:SS.mmm+00:00`.

The same scenario against the port image (`difftest/run-port.sh`) differs from the original only in (a) the order
of nodes whose ids are generated randomly in each run (both sorted by id), (b) the D3 texts (invalid UUID, unknown
field, batch).

## 12. Test plan (proves equivalence)

Unit tests:
- UUID scalar: accepts simple, hyphenated, braced, urn, upper case; rejects `1-2-3-4-5`, 35/37 chars, non-hex;
  serializes lowercase hyphenated.
- DateTime scalar: `…T…:SS+00:00` for whole seconds, `.mmm+00:00` otherwise (e.g. 120 ms → `.120`).
- Header parsing: valid; missing roles; unknown role `"BUYER"`; extra fields; bad uuid; non-JSON → absent.
- `checkPermissions`: buyer self ok; buyer other denied with caller id in message; employee/admin any; empty roles.
- Item connection (integration test; in memory in the original): ASC/DESC unsigned byte order (ids `7fff…` vs `8000…` and `ffff…` vs `0000…` distinguish
  signed vs unsigned), first/skip/hasNextPage table incl. skip > total, first 0.
- Q2 picker: with a fixed `Random`, only the last shuffled element decides; exact-duplicate pairs collapse.

Integration tests (Testcontainers Postgres, full HTTP via `POST /`):
- Seed via events (`/on-topic-event` CloudEvents) then exercise every operation, asserting full JSON responses
  (data + exact error messages + `data: null` on root errors).
- `createShoppingcartItem` twice with the same variant → 2 distinct items (Q1; since C1: the same item); missing user / missing variant
  messages and check order (user before variant); unauthenticated / other buyer / employee.
- `updateShoppingcart`: replace items, new ids, `lastUpdatedAt` changes and equals `addedAt`; `null` list leaves
  cart and `lastUpdatedAt` untouched and reports "ShoppingCart … not found" for unknown users; empty list clears;
  unknown variant as the only element → error; unknown user with valid variants → User-not-found message.
- `updateShoppingcartItem` / `deleteShoppingcartItem` / `shoppingcartItem`: not-found before auth; owner vs other;
  `lastUpdatedAt` unchanged.
- `_entities`: User (no header needed), ShoppingCartItem, ProductVariant → `Entity not found.`, missing
  `__typename`, invalid id, mixed list with one unknown user → whole field fails.
- **Order contract test**: send the exact order request body/headers of §5 and compare the JSON with the expected
  document (all items, id ASC, `__typename` present).
- Events: user/variant created (200 `{"status":0}`), duplicate → 500, unknown topic → 500, malformed → 400/415/422,
  order-created deletes only listed items of that user, unknown user → 200, redelivery → 200.
- `GET /dapr/subscribe` byte-identical; `GET /health` 200.
- SDL: `tools/sdl-diff.sh shoppingcart` empty.

Differential check (only when `misarch-optimization/MEASURING.lock` is absent): `difftest/run-original.sh` and
`difftest/run-port.sh` replay `difftest/scenario.py` against `ghcr.io/misarch/shoppingcart:main` + `mongo:6.0` and
against the port image + Postgres; `difftest/original-responses.jsonl` is the recorded original output.

Implemented as: `CompatUnitTest` (2), `ShoppingcartIntegrationTest` (17), `ShoppingcartSdlTest` (1) - all green
(after the defect fixes: `CompatUnitTest` 1, the Q2 picker test is gone, 19 in total); the
unit tests of the shared helpers (uuid/chrono/header parsing) moved to libs/common (`RustUuidAndTimeTest`,
`AsyncGraphqlCompatTest`).

## 13. Implementation notes (async-graphql compatibility, `org.misarch.common.rust.asyncgraphql`)

graphql-kotlin/graphql-java differ from async-graphql in observable ways; the port closes the gaps per service:

| async-graphql / axum behaviour | port |
|---|---|
| `UUID` parsed like the `uuid` crate, `DateTime` = chrono `to_rfc3339` of ms values | `AsyncGraphqlScalars.UUID` / `.DATE_TIME` (`RustUuid.tryParse`, `RustOffsetDateTime`; `ScalarMapping` beans override libs/common's) |
| unparsable `Authorized-User` = no header | `RustAuthorizedUserGraphQLContextFactory` (libs/common's would fail the request) |
| `_entities`: all-or-nothing, own messages | `AsyncGraphqlEntitiesDataFetcher`, swapped in by `AsyncGraphqlSemanticsInstrumentation` |
| any field error nulls `data`, only the first error is reported, `_entities` paths without index, `find_entity` errors without path | `AsyncGraphqlSemanticsInstrumentation.instrumentExecutionResult` |
| mutation fields after a failed one are not executed | `AsyncGraphqlSemanticsInstrumentation.instrumentDataFetcher` (flag in the GraphQL context) |
| `"data": null` always written | Jackson mix-in on graphql-kotlin's `GraphQLResponse` |
| negative `Int` for unsigned Rust args: field error at the argument value | `Messages.requireUnsigned` + `AsyncGraphqlDataFetcherExceptionHandler` |
| `application/graphql-response+json`, GraphiQL at `GET /`, empty `/health`, exact `/dapr/subscribe`, no batching | `AsyncGraphqlRoutes` (subscriptions from the service's `dapr-subscribe.json`), `NoBatchGraphQLRequestParser`, `graphql.endpoint: /`, actuator endpoints excluded |
| axum `Json` extractor statuses for events | `AxumJsonHandler` (functional routes instead of `@Topic` controllers) |

The layer lives in libs/common (`org.misarch.common.rust.asyncgraphql`, enabled by `@Import(AsyncGraphqlConfiguration::class)`
on `ShoppingcartApplication`) and is shared with the wishlist port; moved unchanged from the former `compat/` package
(`docs/requests/shoppingcart.md`, docs/PORTING.md §11a). `authorize_user` is libs/common's `dfe.rustAuthorizeUser(id)`
(identical messages and semantics).
