# Service spec: `shoppingcart`

Original: `/home/devbox/workspace/misarch/shoppingcart` (Rust 2021, axum 0.8, async-graphql 7.0, MongoDB; image
`ghcr.io/misarch/shoppingcart:main`, built 2025-06-29). Reference SDL: `reference/sdl/shoppingcart.graphql` (live
`_service { sdl }` of the original; the `misarch/schemas` copy additionally marks `ShoppingCart @shareable`).
This service is written like the Kotlin services (entities, `CoroutineCrudRepository`s, services, GraphQL
`Query`/`Mutation`, one `EventController`); fixed defects of the original: `docs/fixes/shoppingcart.md`; every
visible difference to the original: §8.

## 1. Role in the system

- Owns `ShoppingCart` (one per user, no own id) and `ShoppingCartItem`.
- Local replicas fed by events: users (`user/user/created`) and product variants (`catalog/product-variant/created`).
- Removes ordered cart items when `order/order/created` arrives.
- Publishes **no** events and makes **no** outbound calls.
- Serves one synchronous service invocation (S20): order's `createOrder` → `POST /v1.0/invoke/shoppingcart/method/`
  → app `POST /` with `_entities(User){shoppingcart{shoppingcartItems{nodes{id productVariant{id} count}}}}` (§5).
- Workload: every Gatling buy iteration runs `createShoppingcartItem` (gateway) and `createOrder` (order → `_entities`)
  and, after the order, `order/order/created`.

## 2. Runtime contract

| Item | Value |
|---|---|
| HTTP port | 8080 |
| GraphQL | `POST /` (`graphql.endpoint: /`; gateway `routing_url` and order use `/v1.0/invoke/shoppingcart/method/`); GraphiQL at `/graphiql` |
| Health | `GET /health` → `200 {"status":"UP"}` |
| Dapr app id | `shoppingcart`; pub/sub component `pubsub` |
| Subscriptions | `GET /dapr/subscribe` (Dapr SDK): `user/user/created` → `/subscription/user/user/created`, `catalog/product-variant/created` → `/subscription/catalog/product-variant/created`, `order/order/created` → `/subscription/order/order/created` |
| Env | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD` (replace `MONGODB_URI`), `OTEL_*` |
| Auth input | `Authorized-User: {"id":"<uuid>","roles":[...]}` (gateway; forwarded by order) |

## 3. GraphQL schema

`tools/sdl-diff.sh shoppingcart` is empty. Summary:

- `Query.shoppingcartItem(id: UUID!): ShoppingCartItem!`; `_entities` for `User` and `ShoppingCartItem`
  (`ProductVariant` is `@key(resolvable: false)`).
- `Mutation.updateShoppingcart(input: UpdateShoppingCartInput!): ShoppingCart!`,
  `createShoppingcartItem(input: CreateShoppingCartItemInput!): ShoppingCartItem!`,
  `updateShoppingcartItem(input: UpdateShoppingCartItemInput!): ShoppingCartItem!`,
  `deleteShoppingcartItem(id: UUID!): Boolean!`.
- `User { id, shoppingcart: ShoppingCart! }`, `ShoppingCart { lastUpdatedAt, shoppingcartItems(first, skip, orderBy:
  CommonOrderInput): ShoppingCartItemConnection! }`, `ShoppingCartItem { id, count, addedAt, productVariant }`.
- The SDL has its own `OrderDirection`/`CommonOrderInput`/`CommonOrderField` (descriptions of the original), so the
  service keeps them in `ShoppingCartItemConnection.kt` instead of libs/common's `CommonOrder`; `scalar UUID` and
  `scalar DateTime` have no descriptions (`misarch.graphql.scalar-descriptions: false`).

## 4. Operations and authorization

Authorization like the Kotlin services: `dfe.authorizedUser` (no header → `Unauthorized access`), owner or
employee/admin (`checkIsEmployee()`). Errors are graphql-java's `Exception while fetching data (/path) : <message>`.

| Operation | Behaviour |
|---|---|
| `_entities(User)` | no authorization; `UserDataLoader` (the user must be replicated; unknown → `null` + error) |
| `_entities(ShoppingCartItem)` | no authorization; `ShoppingCartItemDataLoader` |
| `shoppingcartItem(id)` | data loader (unknown → `NoSuchElementException`), then owner or employee |
| `createShoppingcartItem` | owner or employee (Mutation); service, one transaction: `count >= 0`, lock the user row (unknown → `User with id <id> does not exist`), product variant must be replicated (`Product variant with id <id> does not exist`); the user's first item (by `addedAt`, id) of the variant is returned unchanged if it exists, else the item is inserted and `lastUpdatedAt = addedAt` |
| `updateShoppingcart` | owner or employee; if `shoppingCartItems` is given: identical `(count, productVariantId)` pairs collapse, `count >= 0`, every product variant must exist (the first unknown one in input order is reported), then in one transaction `UPDATE UserEntity SET shoppingcartLastUpdatedAt` (locks the cart; 0 rows → `User with id <id> does not exist`), delete all items, insert the new ones (`addedAt = lastUpdatedAt`). Returns the cart (unknown user with `shoppingCartItems: null` → `NoSuchElementException`) |
| `updateShoppingcartItem` | `count >= 0`, item must exist, owner or employee (service, like address's `archiveUserAddress`); the cart's `lastUpdatedAt` is set first (locks the user row), then the count |
| `deleteShoppingcartItem` | item must exist, owner or employee; `lastUpdatedAt` set, item deleted, `true` |
| `ShoppingCart.shoppingcartItems` | `BaseConnection` over the user's `ShoppingCartItemEntity` rows, ordered by `id` (ASC unless `orderBy.direction: DESC`); no arguments → all items; `PagingValidation.NON_NEGATIVE` (`first: 0` gives an empty page, negative values `Limit/Offset must not be negative.`); `nodes`, `totalCount`, `hasNextPage` are one statement each, run only when selected |

`count: 0` is accepted (as in the original).

## 5. Service invocation served (order → shoppingcart, S20)

Order sends `{"variables":{"representations":[{"__typename":"User","id":"<userId>"}]},"query":"query
GetShoppingCartProductVariantIdsAndCounts($representations: [_Any!]!) { _entities(...) { __typename ... on User {
shoppingcart { shoppingcartItems { nodes { id, productVariant { id }, count } } } } } }","operationName":"..."}`
with the buyer's `Authorized-User` header. Answer: `{"data":{"_entities":[{"__typename":"User","shoppingcart":
{"shoppingcartItems":{"nodes":[{"id":"…","productVariant":{"id":"…"},"count":1}, …]}}}]}}`, all items sorted by id.
Statements: the user row (data loader) and one page of the items. An unknown user gives `_entities: [null]` and an
error; order's `createOrder` fails in both the original and this form ("Response data … is empty").

## 6. Persistence

Flyway: `V1_1__create_relations.sql`.

```sql
ProductVariantEntity (id UUID PRIMARY KEY)                                   -- replica
UserEntity (id UUID PRIMARY KEY, shoppingcartLastUpdatedAt TIMESTAMPTZ NOT NULL)  -- replica + the user's cart
ShoppingCartItemEntity (id UUID PRIMARY KEY DEFAULT uuid_generate_v4(), userId UUID NOT NULL REFERENCES UserEntity,
    count BIGINT NOT NULL, addedAt TIMESTAMPTZ NOT NULL, productVariantId UUID NOT NULL)   -- index on userId
```

Repositories: `UserRepository` (`createUser` `ON CONFLICT DO NOTHING`, `lockById` `FOR UPDATE`,
`updateShoppingcartLastUpdatedAt`), `ShoppingCartItemRepository` (`findFirstByUserIdAndProductVariantIdOrderByAddedAtAscIdAsc`,
`deleteByUserId`, `deleteByUserIdAndIdIn` with the ids as one array parameter), `ProductVariantRepository`
(`createProductVariant`). Product variant ids are validated with one `ConditionQueries` query (`id = ANY(?)`).

Write paths (statements per call): `createShoppingcartItem` 5 (lock user, variant check, item lookup, INSERT, UPDATE
user) or 3 when the item exists; `order/order/created` 2 (one `DELETE … id = ANY(?)`, UPDATE user if rows were
deleted); `updateShoppingcart` 4 + one INSERT per item.

## 7. Events

Published: none. Consumed (CloudEvents, Jackson; unknown fields ignored):

| Topic | `data` fields read | Handler |
|---|---|---|
| `user/user/created` | `id` | `UserService.registerUser`: user with an empty cart, `lastUpdatedAt = now`; idempotent |
| `catalog/product-variant/created` | `id` | `ProductVariantService.registerProductVariant`; idempotent |
| `order/order/created` | `userId`, `orderItems[].shoppingCartItemId` | `ShoppingCartService.removeOrderedItems`: deletes the listed items of that user's cart (other ids are ignored), sets `lastUpdatedAt` if any were deleted |

Answers: 200 (empty body) on success, 400 for an unreadable body, 500 on a database error (Dapr retries).

## 8. Deviations from the original

Project-wide: Postgres instead of MongoDB (env vars above), OpenTelemetry Java agent, Dapr SDK endpoints
(`/dapr/config`, `/healthz`). Visible through the API:

- **Event routes**: `/subscription/<topic>` per topic instead of the shared `/on-topic-event` and
  `/on-order-creation-event`; `/dapr/subscribe` is generated by the Dapr SDK (same topics and pub/sub component).
  Success answers are 200 with an empty body (was `{"status":0}`); malformed bodies 400 (was 415/400/422); an unknown
  topic on a route cannot occur any more (was 500).
- **HTTP surface**: `/health` answers `{"status":"UP"}` (was an empty 200); GraphiQL at `/graphiql` (was `GET /`);
  GraphQL responses are `application/json` (was `application/graphql-response+json`); request batching as in
  graphql-kotlin.
- **Error shape** (graphql-java instead of async-graphql): field errors read `Exception while fetching data (/path) :
  <message>`; a failing root field no longer nulls the whole `data`, all errors are reported, and later mutation
  fields of the same request still run; `_entities` resolves an unknown user/item to `null` with an error (was:
  the whole field fails with `data: null`).
- **Error texts**: `Unauthorized access` / `Unauthorized access: <id> is not an employee or admin` (were
  `Authentication failed …`); `User with id <id> does not exist`, `Product variant with id <id> does not exist`,
  `Count must not be negative`, `Limit/Offset must not be negative.` (paging), and `null` (`NoSuchElementException`)
  for an unknown item or cart (were the Rust type paths, `… not found.`, `… failed in MongoDB.` and async-graphql's
  `Failed to parse "Int": Invalid number` located at the argument).
- **Authorized-User header**: an unparsable header fails the request (was: treated as absent); an unknown role is
  simply not employee/admin.
- **Scalars**: `UUID` input is parsed by `UUID.fromString` (the uuid crate also accepted simple, braced and URN
  forms); `DateTime` is written as `yyyy-MM-ddTHH:mm:ss.SSSZ` (was chrono's `…+00:00`, without fraction for whole
  seconds); timestamps keep the database's microseconds internally (the original stored milliseconds).
- `count`, `first`, `skip` are 32-bit `Int`s (the original's `u32`/`usize` accepted up to 2^32-1; the gateway sends
  32-bit values only).
- The defects of `docs/fixes/shoppingcart.md` are fixed (item reuse per variant, all variants validated, idempotent
  replica events, `lastUpdatedAt` on every cart change).

## 9. Tests

`tools/build.sh :services:shoppingcart:test`: `ShoppingcartIntegrationTest` (Testcontainers Postgres, fake Dapr
sidecar: subscriptions, health, idempotent replica events, order event incl. its statements, create/update/delete
with authorization and validation, item reuse and concurrent creation, connection ordering and paging, statements of
the user entity and of order's S20 query, entities, the exact S20 request, `updateShoppingcart` set semantics and
validation) and `ShoppingcartSdlTest` (`tools/sdl-diff.sh shoppingcart` empty).
