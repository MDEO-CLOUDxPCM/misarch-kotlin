# wishlist (Kotlin port)

Port of the Rust service `misarch/wishlist` (axum + async-graphql + MongoDB + mongodb-cursor-pagination). Behaviour
spec, quirks and the Mongo→Postgres mapping: [docs/specs/wishlist.md](../../docs/specs/wishlist.md).

## Contract (unchanged)

- `POST /` GraphQL (gateway: `/v1.0/invoke/wishlist/method/`), `GET /` GraphiQL, `GET /health` (200, empty body),
  port 8080, Dapr app id `wishlist`.
- Subscriptions (`GET /dapr/subscribe`, byte-identical): `user/user/created`, `catalog/product-variant/created` →
  `POST /on-topic-event`. Publishes nothing, invokes nothing.
- Env: `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD` (replace
  `MONGODB_URI`), `OTEL_*`.

## Layout

Same as [shoppingcart](../shoppingcart/README.md) (the same async-graphql compatibility layer,
`org.misarch.common.rust.asyncgraphql` in libs/common, formerly the local `compat/` package):
`graphql/` (Query `wishlist`, Mutation `create/update/deleteWishlist`, `User.wishlists`, `Wishlist.productVariants`,
entity resolvers for `User` and `Wishlist`), `persistence/` (`WishlistEntity` with a `UUID[]` of product variants,
`UserEntity`, `ProductVariantEntity`), `service/` (`WishlistService`, `Messages`), `event/`, `difftest/`.

## What differs from the original

Same engine/runtime deviations as shoppingcart (PostgreSQL, 32-bit `Int`, graphql-java request-level error texts,
no batching body, OTel agent, Spring 404s; see the spec, section 9), plus: a wishlist `name` containing U+0000 is
rejected by Postgres (MongoDB stored it).

Reproduced on purpose: `User.wishlists` follows `mongodb-cursor-pagination` 0.3.2 exactly - default page size 25,
`_id DESC` tie-breaker for every order field except `ID` (so `USER_ID` sorts by `_id DESC` in both directions),
binary name order (`COLLATE "C"`), and with `skip > 0` it returns `first + 1` wishlists when
`skip + first + 1 == totalCount`; product variant validation checks only one (random) id of the list; `_entities(User)`
is unauthenticated but needs the user in the local replica; existence is checked before authorization; duplicate
replication events answer 500; error texts with Rust type paths.

## Verification

- `tools/build.sh :services:wishlist:test`: `CompatUnitTest`, `WishlistIntegrationTest` (Testcontainers; includes the
  pagination table and all orderings recorded from the original), `WishlistSdlTest` (`tools/sdl-diff.sh wishlist`
  empty).
- `difftest/`: the 68-request scenario against `ghcr.io/misarch/wishlist:main` (recorded in
  `original-responses.jsonl`) and against the port image; only run outside measurement windows.
