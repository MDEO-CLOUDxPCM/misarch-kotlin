# Port spec: `wishlist`

Original: `/home/devbox/workspace/misarch/wishlist` (Rust 2021, axum 0.8, async-graphql 7.0 with features
`bson, chrono, uuid, log`, mongodb driver 2.8.2, mongodb-cursor-pagination 0.3.2, bson 2.x). The deployed image
`ghcr.io/misarch/wishlist:main` (built 2025-06-29) contains async-graphql 7.0.17, axum 0.8.4, bson 2.15.0,
chrono 0.4.41, uuid 1.17.0, mongodb-cursor-pagination 0.3.2. Binary/crate name `misarch_wishlist` (leaks into
error messages, see §4). Reference SDL: `/home/devbox/workspace/misarch/schemas/wishlist.graphql`; it matches the code.

The code is the truth. Where `docs/.../services/wishlist.mdx` disagrees, this spec follows the code.

## 1. Role in the system

- Owns `Wishlist` (name + set of product variant ids, belongs to one user).
- Keeps two local replicas fed by events: `users` (`user/user/created`) and `product_variants`
  (`catalog/product-variant/created`).
- Publishes **no** events, makes **no** outbound calls, serves **no** service invocation except the gateway's
  GraphQL calls.
- Workload: the Gatling buy scenario does not touch it; the calibration load generator has `wishlistView`
  (`wishlist(id){… productVariants(first: 10)…}`) and `addToWishlist` (`updateWishlist(productVariantIds)`).
  The frontend uses `user{wishlists(first,skip,orderBy)}`, `wishlist(id)`, `createWishlist`, `updateWishlist`,
  `deleteWishlist`.

## 2. Runtime contract (must stay identical)

| Item | Value |
|---|---|
| HTTP port | 8080 (hard-coded, `0.0.0.0:8080`) |
| GraphQL | `POST /` (gateway `routing_url: http://localhost:3500/v1.0/invoke/wishlist/method/`). `GET /` serves GraphiQL (HTML). No `/graphql` route. |
| Health | `GET /health` → `200`, empty body (no DB check). Docker healthcheck `wget -qO - http://localhost:8080/health`. |
| Dapr app-id | `wishlist` (`--app-port ${EXPERIMENT_CONFIG_SIDECAR_PORT:-8080}`, `--app-protocol http`) |
| Dapr endpoints served | `GET /dapr/subscribe`, `POST /on-topic-event` |
| Pub/sub component | `pubsub` |
| Env vars (original) | `MONGODB_URI` (required, panics if unset), `OTEL_EXPORTER_OTLP_ENDPOINT` (default `http://localhost:4318`, metrics to `<endpoint>/v1/metrics` every 5 s, resource `service.name=wishlist`) |
| DB (original) | MongoDB database `wishlist-database`, collections `wishlists`, `users`, `product_variants`; app name `Wishlist` |
| CLI | `--generate-schema` writes `./schemas/wishlist.graphql` and exits |
| Auth input | Header `Authorized-User: {"id":"<uuid>","roles":[…]}` from the gateway |

Port: Postgres env vars per `docs/PORTING.md` replace `MONGODB_URI`; everything else above stays identical.

## 3. GraphQL schema (federation v2.5)

Must produce an empty `tools/sdl-diff.sh` against `reference/sdl/wishlist.graphql`. Summary:

- `Query.wishlist(id: UUID!): Wishlist!`
- `Query._entities(representations: [_Any!]!): [_Entity]!`, `union _Entity = ProductVariant | User | Wishlist`
- `Mutation.createWishlist(input: CreateWishlistInput!): Wishlist!`
- `Mutation.updateWishlist(input: UpdateWishlistInput!): Wishlist!`
- `Mutation.deleteWishlist(id: UUID!): Boolean!`
- `type User @key(fields: "id") { id: UUID!, wishlists(first: Int, skip: Int, orderBy: WishlistOrderInput): WishlistConnection! }`
- `type Wishlist @key(fields: "id") { id: UUID!, user: User!, name: String!, createdAt: DateTime!, lastUpdatedAt: DateTime!, productVariants(first: Int, skip: Int, orderBy: CommonOrderInput): ProductVariantConnection! }`
- `type ProductVariant @key(fields: "id", resolvable: false) { id: UUID! }`
- `type WishlistConnection @shareable` / `type ProductVariantConnection @shareable` `{ nodes, hasNextPage: Boolean!, totalCount: Int! }`
- `input CreateWishlistInput { userId: UUID!, productVariantIds: [UUID!]!, name: String! }`
- `input UpdateWishlistInput { id: UUID!, productVariantIds: [UUID!], name: String }`
- `input WishlistOrderInput { direction: OrderDirection, field: WishlistOrderField }`,
  `enum WishlistOrderField { ID USER_ID NAME CREATED_AT LAST_UPDATED_AT }`
- `input CommonOrderInput { direction, field: CommonOrderField }`, `enum CommonOrderField { ID }`, `enum OrderDirection { ASC DESC }`
- Scalars `UUID`, `DateTime`, `_Any`, `FieldSet`, `link__Import`; `enum link__Purpose`. Descriptions must match the
  reference text exactly (note the multi-line block description of `productVariants.skip`).

Scalars are identical to shoppingcart (see `shoppingcart.md` §3): strict Rust-compatible `UUID` parsing,
lowercase output; `DateTime` with millisecond precision formatted as `YYYY-MM-DDTHH:MM:SS[.mmm]+00:00`.
Unsigned argument types: `User.wishlists(first: u32, skip: u64)`, `Wishlist.productVariants(first, skip: usize)`;
negative values are coercion errors.

## 4. Resolvers and authorization (exact semantics)

Authorized-User parsing and `authorize_user` are identical to shoppingcart (`shoppingcart.md` §4.1): any parse
failure = no header; `Authentication failed. Authorized-User header is not set or could not be parsed.`;
``Authentication failed for user of UUID: `<caller id>`. Operation not permitted.``; admin/employee may access
everything, a buyer only their own user id.

Error strings:
- `E_WL_NF` = ``misarch_wishlist::graphql::model::wishlist::Wishlist with UUID: `<id>` not found.``
- `E_USER_NF` = ``misarch_wishlist::graphql::model::user::User with UUID: `<id>` not found.``
- `E_PV` = ``Product variant with the UUID: `<pvId>` is not present in the system.``
- DB errors only (keep text): `Adding wishlist failed in MongoDB.`,
  ``Updating product_variant_ids of wishlist of id: `<id>` failed in MongoDB.``,
  ``Updating name of wishlist of id: `<id>` failed in MongoDB.``, ``Deleting wishlist of id: `<id>` failed in MongoDB.``,
  `Retrieving wishlists failed in MongoDB.`, `Product variants with the specified UUIDs are not present in the system.`

| Operation | Behaviour (first failing step decides the error) |
|---|---|
| `wishlist(id)` | 1. load (missing → `E_WL_NF`) 2. `authorize_user(wishlist.user.id)` 3. return. |
| `_entities(Wishlist)` | Same as `wishlist(id)`, **with** authorization. |
| `_entities(User)` | **No authorization.** User must exist in `users` → else `E_USER_NF`. Returns `User{id}`. |
| `_entities` generic | As shoppingcart: `Entity not found.` (unknown typename incl. `ProductVariant`, missing/invalid id, non-object), `"__typename" must be an existing string.`; all-or-nothing over the list. |
| `User.wishlists(first, skip, orderBy)` | 1. `authorize_user(user.id)` (also when reached via `Wishlist.user`) 2. paginate (§4.1). |
| `Wishlist.user` | `User{id: wishlist.user_id}` (no existence check). |
| `Wishlist.productVariants(first, skip, orderBy)` | In memory in the original (port: SQL connection over the link table, same results, §4.1), identical algorithm to shoppingcart's `shoppingcartItems`: distinct variant ids sorted by **unsigned byte order** ASC (or DESC), `nodes = drop(skip).take(first)`, `totalCount = size`, `hasNextPage = totalCount > nodes.size + skip`; no arguments → all. |
| `createWishlist(input{userId, productVariantIds, name})` | 1. `authorize_user(userId)` 2. dedupe `productVariantIds` (set) 3. **validation quirk Q1**: only the last id of a random iteration order is checked → `E_PV`; empty list passes (**FIXED**: all ids are checked in one query, the first unknown id in input order is reported) 4. user must exist → `E_USER_NF` 5. insert `{id: random v4, user_id, name, created_at = last_updated_at = now(ms), variants}` 6. re-read and return. `name` is not validated (empty allowed). |
| `updateWishlist(input{id, productVariantIds?, name?})` | 1. load (missing → `E_WL_NF`) 2. `authorize_user(wishlist.user.id)` 3. `now` taken once 4. if `productVariantIds` non-null: dedupe, Q1 validation (`E_PV`), replace the variant set, `last_updated_at = now` 5. if `name` non-null: set name, `last_updated_at = now` 6. re-read and return. Both null → unchanged (timestamps too). |
| `deleteWishlist(id)` | 1. load (missing → `E_WL_NF`) 2. authorize 3. delete 4. `true`. |

### 4.1 `User.wishlists` pagination (mongodb-cursor-pagination 0.3.2 semantics)

Filter `user_id = user.id`. Sort: `(field, direction)` with defaults `ID`/`ASC`; field mapping `ID→_id`,
`USER_ID→user._id`, `NAME→name`, `CREATED_AT→created_at`, `LAST_UPDATED_AT→last_updated_at`. If the field is not
`ID`, the crate appends **`_id DESC`** as tie-breaker (so `USER_ID` sorts purely by `_id DESC` regardless of the
direction). `ID` sorts by `_id` in the given direction only. Name comparison is binary (UTF-8 bytes = code points)
→ Postgres `COLLATE "C"`. UUID comparison is byte order (Postgres `uuid` ordering matches).

```
total = COUNT(*) WHERE user_id = :u
if total == 0: return {nodes: [], hasNextPage: false, totalCount: 0}
limit = (first ?: 25) + 1           # default page size 25 when `first` is omitted
s = skip ?: 0
rows = SELECT … ORDER BY … LIMIT limit OFFSET s
hasMore = (s > 0) ? (rows.size + s < total) : (rows.size > limit - 1)
if hasMore: drop the last row
return {nodes: rows, hasNextPage: hasMore, totalCount: total}
```

Consequences (quirks Q2/Q3): without `first` at most 25 wishlists are returned; when `skip > 0` and
`skip + first + 1 == total`, **first + 1** nodes are returned with `hasNextPage: false`.
**Q3 FIXED** (docs/fixes/wishlist.md): `hasMore = rows.size > first` for every `skip`.

Port (relational, docs/PORTING.md §6): `User.wishlists` checks `first`/`skip` and authorizes, then returns a
connection over the user's `WishlistEntity` rows (`ConnectionQuery` of libs/common = the SQL of the Kotlin services'
`BaseConnection`); each field is one statement, run only when selected: `nodes` = `SELECT t.* … WHERE t.userId = :u
ORDER BY <order> LIMIT (first ?: 25) OFFSET skip`, `totalCount` = `SELECT COUNT(t.id) …`, `hasNextPage` =
`SELECT 1 … LIMIT 1 OFFSET (first ?: 25) + skip` - the same results as the fixed algorithm (the extra row exists ⇔ a
row at offset `first + skip`; `total == 0` gives an empty page anyway). A database error of any of the statements is
`Retrieving wishlists failed in MongoDB.` at the path of that subfield (`…wishlists.nodes` etc. instead of
`…wishlists`; only on database failures).

`Wishlist.productVariants(first, skip, orderBy)` (in memory in the original: distinct ids sorted by id, unsigned byte
order, `orderBy.field` ignored, `drop(skip).take(first)`, `hasNextPage = total > nodes + skip`): a connection over the
wishlist's `WishlistToProductVariantEntity` rows ordered by `productVariantId ASC|DESC` (`first` null = all,
`hasNextPage` then `false` without a statement); equivalent results.

## 5. Persistence

### Original (MongoDB `wishlist-database`)

- `wishlists`: `{_id: UUID, user: {_id: UUID}, name: string, created_at: Date, last_updated_at: Date, internal_product_variants: [{_id: UUID}…]}`
- `users`: `{_id: UUID}`; `product_variants`: `{_id: UUID}`. No secondary indexes.

### Port (Postgres, Flyway `V1__init.sql`, `V2__wishlist_product_variants.sql`)

```sql
CREATE TABLE UserEntity (id UUID PRIMARY KEY);
CREATE TABLE ProductVariantEntity (id UUID PRIMARY KEY);
CREATE TABLE WishlistEntity (
    id                UUID PRIMARY KEY,
    userId            UUID        NOT NULL,   -- user._id; no FK (Mongo had none; checked on create only)
    name              VARCHAR     NOT NULL,
    createdAt         TIMESTAMPTZ NOT NULL,   -- ms precision values
    lastUpdatedAt     TIMESTAMPTZ NOT NULL
);
CREATE INDEX WishlistEntity_userId_idx ON WishlistEntity (userId);
-- V2: internal_product_variants (V1: the array column productVariantIds, migrated with DISTINCT and dropped)
CREATE TABLE WishlistToProductVariantEntity (
    wishlistId       UUID NOT NULL REFERENCES WishlistEntity (id),
    productVariantId UUID NOT NULL,           -- no FK (Q1)
    PRIMARY KEY (wishlistId, productVariantId)
);
```

Relational like the Kotlin services (docs/PORTING.md §6; the link table replaces the former `UUID[]` column, which was
read as a whole and paged in memory). Operations (`persistence/WishlistRepository.kt`): events `INSERT` (FIXED:
`ON CONFLICT DO NOTHING`); `wishlist`/`_entities(Wishlist)`/re-reads = the `WishlistEntity` row only, the variants
are read by `productVariants` when selected (§4.1); `createWishlist` = one transaction (`INSERT` wishlist + one
`INSERT` per variant) + re-read; `updateWishlist` = variants (one transaction: `DELETE` the links, one `INSERT` per
variant, `UPDATE lastUpdatedAt`; the original's single `update_one`), then name (`UPDATE`; as the original's two
`update_one`s, the two steps are not one transaction) + re-read; `deleteWishlist` = one transaction (`DELETE` links,
`DELETE` wishlist); `User.wishlists` = the connection of §4.1 with `<order>` = `id <dir>` or
`<column> <dir>, id DESC` (`name COLLATE "C"` for binary string order).

## 6. Quirks and defects (reproduce, do not fix)

- **Q1 – variant validation checks only one id** (**FIXED**, docs/fixes/wishlist.md): the fold ignores its accumulator, so only the last element of
  the `HashSet` iteration order (random per request) is checked; unknown variant ids can be stored. Port: dedupe,
  shuffle with an injectable `Random`, check only the last.
- **Q2 – default page size 25** for `User.wishlists` (from the pagination crate), although the schema suggests "all".
- **Q3 – over-long page** (**FIXED**): `skip > 0 && skip + first + 1 == total` returns `first + 1` nodes.
- **Q4 – `USER_ID` order** degenerates to `_id DESC`; non-`ID` orders tie-break by `_id DESC` even for `ASC`.
- **Q5 – `_entities(User)` is unauthenticated** (but `User.wishlists` authorizes); the existence check uses the
  local `users` replica, so wishlists of users that were never replicated cannot be listed via the gateway.
- **Q6 – createWishlist validates variants before the user.** `updateWishlist`/`deleteWishlist`/`wishlist` check
  existence before authorization.
- **Q7 – error messages leak Rust type paths.**
- **Q8 – events are not idempotent** (duplicate → 500 → Dapr redelivers forever); unknown topic → 500.
  **FIXED** (duplicate → 200 `{"status":0}`, `ON CONFLICT DO NOTHING`); unknown topic still 500.
- **Q9 – `wishlist.user` is never validated against `users` after creation**, and users/variants are never deleted.

## 7. Events

### Published

None.

### Consumed

`GET /dapr/subscribe` (byte-identical):

```json
[{"pubsubName":"pubsub","topic":"user/user/created","route":"/on-topic-event"},{"pubsubName":"pubsub","topic":"catalog/product-variant/created","route":"/on-topic-event"}]
```

| Route | Topic | `data` fields read | Handler |
|---|---|---|---|
| `POST /on-topic-event` | `user/user/created` | `id: UUID` | insert user |
| `POST /on-topic-event` | `catalog/product-variant/created` | `id: UUID` | insert product variant |

Responses as in shoppingcart: `200 {"status":0}`; other topic → 500; DB error → 500 (duplicate: 200 since the fix); 415/400/422 for
bodies rejected by the JSON extractor.

## 8. Service invocations made / served

None made. Served: only GraphQL `POST /` from the gateway.

## 9. Deviations (final)

As shoppingcart D1-D7 (`shoppingcart.md` §10: PostgreSQL, 32-bit `Int`, engine texts for syntax/validation and
invalid UUIDs, batch body, event 4xx bodies, Spring 404/Dapr SDK endpoints, OTel agent, sequential `_entities`),
plus:
- **D8** a `name` containing U+0000 is rejected by Postgres (`VARCHAR`); MongoDB stored it. Practically irrelevant.
- **D9** relational access (docs/PORTING.md §6): a wishlist read is its row; variants and the user's wishlists are
  connection statements (page / count / probe, only the selected ones) instead of one document read per wishlist
  and an eager count + page. Results identical; the statements of one query are not one snapshot (a concurrent
  write between them can make `totalCount` and `nodes` disagree, as in the Kotlin services); a database error of
  `User.wishlists` is reported at the subfield's path (§4.1).

The async-graphql execution semantics described in §10 (data null + first error only, `_entities` paths without
index, mutation abort, argument-located parse errors) are reproduced by the shared async-graphql layer
`org.misarch.common.rust.asyncgraphql` in libs/common (formerly the `compat/` package)
(`shoppingcart.md` §13).

## 10. Verified against the running original (2026-10-13)

`ghcr.io/misarch/wishlist:main` + `mongo:6.0` in isolation, 68-step scenario (`services/wishlist/difftest/`).
Confirmed: unsigned variant order (`0b…, 7f…, 80…, a1…, f3…`), `NAME ASC` = `"", "Zeta", "alpha", "alpha", "beta",
"beta", "omega", "äpfel"` (binary; equal names tie-broken by `_id DESC`), `USER_ID` ASC and DESC both = `_id DESC`,
`first:3 skip:4` of 8 → 4 nodes / `hasNextPage:false` (Q3), `first:0 skip:7` of 8 → 1 node, `first:0` → `[]` +
`hasNextPage:true`, `createWishlist` for an unknown user with `productVariantIds: []` → `E_USER_NF`.
Wire details:

- Error objects `{"message","locations","path"}`, no `extensions`, `data: null` on any root failure.
- An error **inside** an entity's sub-selection (e.g. `User.wishlists` authorization during `_entities(User)`)
  makes the whole `data` null and has `"path":["_entities","wishlists"]` (no list index) - reproduced
  (graphql-java alone would null only the list element and report `["_entities",0,"wishlists"]`).
- Negative `first` → `Failed to parse "Int": Invalid number` as a field error at `["_entities","wishlists"]`,
  located at the argument value (column of `-1`) - reproduced.
- Same HTTP details as shoppingcart (`application/graphql-response+json`, empty `/health`, GraphiQL at `/`).

## 11. Test plan (proves equivalence)

Unit: shared scalar/header/permission tests (as shoppingcart); variant connection argument checks; pagination
algorithm table (total 0; no first → 25; first 0; skip 0 vs skip > 0; `skip + first + 1 == total` → first + 1
nodes; skip ≥ total); order mapping incl. `_id DESC` tie-break; Q1 picker with fixed `Random`.

Integration (Testcontainers Postgres, HTTP `POST /`):
- Seed users/variants through `/on-topic-event` CloudEvents.
- `createWishlist`: success (full JSON, `createdAt == lastUpdatedAt`, dedup of ids, variants sorted), unknown
  variant as the only id → `E_PV`, unknown user with valid variants → `E_USER_NF`, auth matrix (none, other buyer,
  self, employee, admin).
- `updateWishlist`: name only, ids only, both, neither (timestamps unchanged), unknown wishlist → `E_WL_NF`
  before auth, other buyer → denied.
- `deleteWishlist`, `wishlist(id)`, `_entities(Wishlist)` (with auth), `_entities(User)` without header,
  `User.wishlists` via `_entities` with and without header, all five order fields × both directions with ties
  (equal names / equal timestamps), names with non-ASCII characters (byte order), the pagination quirks.
- `Wishlist.productVariants` first/skip/orderBy; unsigned UUID order.
- Events: success body, duplicate → 500, unknown topic → 500, malformed → 400/415/422.
- `GET /dapr/subscribe` byte-identical; `GET /health`.
- SDL: `tools/sdl-diff.sh wishlist` empty.

Differential check (only when `architecture-optimization/MEASURING.lock` is absent): `difftest/run-original.sh` /
`difftest/run-port.sh` replay `difftest/scenario.py`; `difftest/original-responses.jsonl` is the recorded original.

Implemented as: `CompatUnitTest` (2), `WishlistIntegrationTest` (11), `WishlistSdlTest` (1); the unit tests of the
shared helpers moved to libs/common (`RustUuidAndTimeTest`, `AsyncGraphqlCompatTest`).
