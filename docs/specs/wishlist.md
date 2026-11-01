# Service spec: `wishlist`

Original: [MiSArch/wishlist](https://github.com/MiSArch/wishlist) (Rust 2021, axum 0.8, async-graphql 7.0, mongodb driver 2.8.2,
mongodb-cursor-pagination 0.3.2; image `ghcr.io/misarch/wishlist:main`, built 2025-06-29). Reference SDL:
`reference/sdl/wishlist.graphql` (live `_service { sdl }`; it matches `misarch/schemas/wishlist.graphql`).

This service is written like the Kotlin services (entities, `CoroutineCrudRepository`s, services, GraphQL
`Query`/`Mutation`, one `EventController`). §1-§8 describe the original (and the relational tables of the earlier
version); §9 is the implementation, §10 lists every visible difference to the original. Fixed defects:
`docs/fixes/wishlist.md`.

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

## 2. Runtime contract of the original (kept except for §10)

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

Now: Postgres env vars per `docs/PORTING.md` replace `MONGODB_URI`; everything else above stays identical.

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

## 4. Resolvers and authorization of the original

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
| `Wishlist.productVariants(first, skip, orderBy)` | In memory in the original (now: SQL connection over the link table, same results, §4.1), identical algorithm to shoppingcart's `shoppingcartItems`: distinct variant ids sorted by **unsigned byte order** ASC (or DESC), `nodes = drop(skip).take(first)`, `totalCount = size`, `hasNextPage = totalCount > nodes.size + skip`; no arguments → all. |
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

Now (relational, docs/PORTING.md §6): `User.wishlists` checks `first`/`skip` and authorizes, then returns a
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

### Earlier version (Postgres)

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
CREATE INDEX wishlistEntity_userId ON WishlistEntity (userId);
-- internal_product_variants (earlier: the array column productVariantIds)
CREATE TABLE WishlistToProductVariantEntity (
    wishlistId       UUID NOT NULL REFERENCES WishlistEntity (id),
    productVariantId UUID NOT NULL,           -- no FK (Q1)
    PRIMARY KEY (wishlistId, productVariantId)
);
```

The link table replaces the former `UUID[]` column. Current tables, statements and transactions: §9.

## 6. Quirks and defects of the original

- **Q1 – variant validation checks only one id** (**FIXED**, docs/fixes/wishlist.md): the fold ignores its accumulator, so only the last element of
  the `HashSet` iteration order (random per request) is checked; unknown variant ids can be stored. Faithful version: dedupe,
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

## 9. Implementation (services/wishlist)

Tables (Flyway `V1_1__create_relations.sql`):

```sql
UserEntity (id UUID PRIMARY KEY)                                    -- replica, user/user/created
ProductVariantEntity (id UUID PRIMARY KEY)                          -- replica, catalog/product-variant/created
WishlistEntity (id UUID PRIMARY KEY DEFAULT uuid_generate_v4(), userId UUID NOT NULL, name TEXT NOT NULL,
    createdAt TIMESTAMPTZ NOT NULL, lastUpdatedAt TIMESTAMPTZ NOT NULL)          -- index on userId
WishlistToProductVariantEntity (wishlistId UUID NOT NULL REFERENCES WishlistEntity, productVariantId UUID NOT NULL,
    PRIMARY KEY (wishlistId, productVariantId))
```

- Entities `WishlistEntity`, `UserEntity`, `ProductVariantEntity`; the link table is
  `WishlistToProductVariantEntity(@Id key: WishlistToProductVariantKey(wishlistId, productVariantId))`, `Persistable`
  with `isNew() = true`, `CoroutineCrudRepository<…, WishlistToProductVariantKey>` (`findByKeyWishlistId`; `createAll`
  inserts all variant links of a wishlist with one `INSERT … SELECT :wishlistId, unnest(:ids)`; `deleteByWishlistId`).
- `WishlistService` (`@Transactional`): `createWishlist` (ids distinct, all product variants validated with one
  `ConditionQueries` query `id = ANY(?)`, the user must be replicated, INSERT wishlist, one INSERT of the links: 4
  statements), `updateWishlist` (`SELECT … FOR UPDATE` of the wishlist = existence + lock, owner or employee/admin,
  if variants are given: validate, one DELETE, one INSERT; name; `lastUpdatedAt = now` and UPDATE if anything was
  given), `deleteWishlist` (lock, owner check, delete links, delete wishlist). Concurrent changes of one wishlist run
  one after the other (last writer wins; `WishlistConcurrencyTest`).
- `Mutation.createWishlist` checks owner or employee/admin; `Query.wishlist` and the `Wishlist` entity resolver load
  through `WishlistDataLoader` and then check owner or employee/admin; `User.wishlists` checks it on the field.
- `Wishlist.productVariants`: `ProductVariantConnection` over `ProductVariantEntity` joined with the link table
  (`INNER JOIN WishlistToProductVariantEntity j ON j.productVariantId = t.id`, `j.wishlistId = ?`), ordered by id,
  all variants without `first`; `User.wishlists`: `WishlistConnection` over `WishlistEntity` (`userId = ?`), default
  page size 25, order columns `id`, `userId`, `name`, `createdAt`, `lastUpdatedAt`, ties by `id DESC`. Both
  `PagingValidation.NON_NEGATIVE`; each field one statement, run only when selected.
- `UserResolver`: `DataLoaderEntityResolver` (the user must be replicated).

## 10. Deviations from the original

Project-wide: Postgres instead of MongoDB (env `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`,
`SPRING_R2DBC_PASSWORD` replace `MONGODB_URI`), OpenTelemetry Java agent, Dapr SDK endpoints (`/dapr/config`,
`/healthz`). Visible through the API:

- **Event routes**: `/subscription/<topic>` per topic instead of the shared `/on-topic-event`; `/dapr/subscribe` is
  generated by the Dapr SDK (same topics and pub/sub component). Success answers are 200 with an empty body (was
  `{"status":0}`); malformed bodies 400 (was 415/400/422); unknown topics on a route cannot occur any more.
- **HTTP surface**: `/health` answers `{"status":"UP"}` (was an empty 200); GraphiQL at `/graphiql` (was `GET /`);
  GraphQL responses `application/json` (was `application/graphql-response+json`); batching as in graphql-kotlin.
- **Error shape** (graphql-java): `Exception while fetching data (/path) : <message>`; a failing root field no longer
  nulls the whole `data`, all errors are reported, later mutation fields still run; `_entities` resolves an unknown
  user/wishlist, or a wishlist the caller may not read, to `null` with graphql-kotlin's `Exception was thrown while
  trying to resolve federated type …` (was: the whole field fails with the resolver's message).
- **Error texts**: `Unauthorized access` / `Unauthorized access: <id> is not an employee or admin` (were
  `Authentication failed …`); `Product variant with id <id> does not exist`, `User with id <id> does not exist`,
  `null` (`NoSuchElementException`) for an unknown wishlist, `Limit/Offset must not be negative.` (were the Rust type
  paths, `… failed in MongoDB.` and async-graphql's `Failed to parse "Int": Invalid number`); an unparsable
  `Authorized-User` header fails the request (was: treated as absent).
- **Scalars**: `UUID` input via `UUID.fromString` (the uuid crate also accepted simple, braced and URN forms);
  `DateTime` written as `yyyy-MM-ddTHH:mm:ss.SSSZ` (was chrono's `…+00:00`).
- **`NAME` order** uses the database collation (was MongoDB's byte order; `name COLLATE "C"` in the earlier version).
- **`Wishlist.productVariants`** lists the variants known to the replica (inner join); a link to a variant missing
  from the replica, which only data written before the validation fix (docs/fixes/wishlist.md §1) can contain, is not
  listed.
- `first`, `skip` are 32-bit `Int`s (the original's `usize` accepted larger values; the gateway sends 32-bit only).
- The defects of `docs/fixes/wishlist.md` are fixed.

## 11. Tests

`tools/build.sh :services:wishlist:test`: `WishlistIntegrationTest` (subscriptions, health, idempotent replica
events, create with authorization/validation/result and its statements, `wishlist`/entity authorization,
`User` entity and `User.wishlists` authorization, order fields, ties, paging, default 25, product variant
connection, lazy statements, update/delete incl. the statements of a variant replacement),
`WishlistConcurrencyTest` (concurrent updates, update racing a delete), `WishlistSchemaTest` (generated ids, one
link per variant and wishlist), `WishlistSdlTest` (`tools/sdl-diff.sh wishlist` empty).
