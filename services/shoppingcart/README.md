# shoppingcart (Kotlin port)

Port of the Rust service `misarch/shoppingcart` (axum + async-graphql + MongoDB). Behaviour spec, quirks and the
Mongo→Postgres mapping: [docs/specs/shoppingcart.md](../../docs/specs/shoppingcart.md).

## Contract (unchanged)

- `POST /` GraphQL (the gateway and order's `createOrder` call `/v1.0/invoke/shoppingcart/method/`), `GET /` GraphiQL,
  `GET /health` (200, empty body), port 8080, Dapr app id `shoppingcart`.
- Subscriptions (`GET /dapr/subscribe`, byte-identical): `user/user/created` and `catalog/product-variant/created` →
  `POST /on-topic-event`, `order/order/created` → `POST /on-order-creation-event`. Publishes nothing, invokes nothing.
- Env: `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD` (replace
  `MONGODB_URI`), `OTEL_*`.

## Layout

```
(libs/common) org.misarch.common.rust.asyncgraphql, enabled by @Import(AsyncGraphqlConfiguration::class): the
              async-graphql / axum / serde / uuid / chrono behaviour of the Rust original on top of graphql-kotlin
              (Rust UUID + DateTime scalars, lenient Authorized-User parsing, `_entities` + error semantics,
              mutation abort, `"data": null`, argument-parse errors, axum Json rejections, GET / and /health,
              byte-identical /dapr/subscribe from this service's resources/dapr-subscribe.json,
              `application/graphql-response+json`, no batching); formerly the local `compat/` package
event/        Dapr routes (functional, axum status codes 200/415/400/422/500)
graphql/      Query, Mutation, model (User, ShoppingCart, ShoppingCartItem, ProductVariant, connection, order
              inputs), input, federation (User / ShoppingCartItem entity resolvers)
persistence/  SQL (DatabaseClient): UserEntity (+ flattened cart timestamp), ShoppingCartItemEntity,
              ProductVariantEntity
service/      business logic in the original's order of checks; Messages = the original's error texts
difftest/     differential harness: the same request scenario against the original image and the port
```

## What differs from the original

Deliberate / unavoidable (details and impact in the spec, section 10):

1. PostgreSQL instead of MongoDB (one row per user + one row per cart item; relational access like the Kotlin
   services: a user/cart read is its `UserEntity` row, the items are read by `shoppingcartItems` only when selected).
   DB-error texts still say "MongoDB".
2. GraphQL `Int` is 32-bit in graphql-java: `count`/`first`/`skip` values above 2147483647 (accepted by the Rust
   `u32`/`usize`) are rejected. Through the gateway such values never arrive (GraphQL-JS validates 32-bit).
3. Engine-level texts: syntax/validation errors and invalid `UUID` input are request-level graphql-java errors
   (original: async-graphql texts, invalid UUIDs as field errors); batched requests get `400` with an empty body
   (original: body `UnsupportedBatch`); event-handler `4xx` bodies are not serde's texts (status codes are equal).
4. Metrics/tracing come from the OpenTelemetry Java agent (original: axum-otel-metrics, OTLP push every 5 s).
5. Unknown paths answer Spring's `404` JSON (original: empty `404`); Dapr's `/dapr/config` and `/healthz` exist
   (Dapr SDK), the original had none.

Defects of the original fixed in the port (docs/fixes/shoppingcart.md): `createShoppingcartItem` returns the existing
item of a variant instead of appending a new one; `updateShoppingcart` validates every product variant; duplicate
replication events are idempotent (200); `lastUpdatedAt` changes with every cart change. Still as in the original:
`count: 0` accepted; `_entities` unauthenticated and all-or-nothing; variants validated before the user; error texts
with Rust type paths (`misarch_shoppingcart::graphql::model::user::User with UUID: ...`). The `difftest/` recordings
describe the original (before the fixes).

## Verification

- `tools/build.sh :services:shoppingcart:test`: unit (`CompatUnitTest`), Testcontainers integration
  (`ShoppingcartIntegrationTest`: every route, mutation, query, entity, error text/location, the order invocation
  contract, events), SDL (`ShoppingcartSdlTest`, `tools/sdl-diff.sh shoppingcart` empty).
- `difftest/`: `run-original.sh` (`ghcr.io/misarch/shoppingcart:main` + `mongo:6.0`) and `run-port.sh`
  (`misarch-kotlin/shoppingcart:dev` + Postgres) replay `scenario.py` (72 requests) and write normalized responses;
  `original-responses.jsonl` is the recorded answer of the original. Differences left: node order where it depends
  on the randomly generated ids, and the engine-level texts of item 3. Only run outside measurement windows.
