# order (Kotlin port)

Port of the Rust order service (`misarch/order`: axum, async-graphql, MongoDB) to the uniform stack (Spring Boot 4,
graphql-kotlin, R2DBC/Postgres). The behavioural spec and the full list of quirks are in
[docs/specs/order.md](../../docs/specs/order.md).

## What stays identical

- GraphQL at `POST /` (gateway: `localhost:3500/v1.0/invoke/order/method/`). The federation SDL is semantically equal to
  the live SDL and to misarch/schemas (`tools/sdl-diff.sh order`, `--schemas`).
- Dapr app id `order`, `GET /dapr/subscribe` byte-identical to the original (8 subscriptions, original order). The
  unsubscribed route `/on-shipment-creation-failed-event` is served as well.
- Event routes behave like axum's `Json<Event<T>>`: 415 for a wrong content type, 400 for invalid JSON, 422 for a
  shape mismatch, 500 for an unexpected topic or a handler failure, 200 `{"status":0}` on success.
- `createOrder` makes 4 sequential invocations (shoppingcart `/`, inventory, discount and shipment `/graphql`). The
  bodies are graphql_client's `{"variables","query","operationName"}` with the verbatim query files
  (`src/main/resources/queries`), and the caller's `Authorized-User` is re-serialized for the shoppingcart only.
- `placeOrder` publishes `order/order/created` with serde's JSON. This includes `"paymentAuthorization":{"cVC":123}`,
  `null`s and chrono `…Z` timestamps in milliseconds. A test compares it with the event captured from the running
  original (`reference/events/order_order_created.json`).
- Error messages are copied verbatim, including Rust type names (`misarch_order::graphql::model::order::Order with
  UUID: … not found.`). Validation order, pagination (`mongodb-cursor-pagination` semantics, default page size 25) and
  arithmetic (`price × discount`, `as u64` truncation, u32 wrap of `orderAmount`) follow the original.
- The original's defects are reproduced: `orderItem` always fails, `catalog/product-variant/updated` is always
  rejected (422), address ids are not validated, shipment fees are discarded, placement after 1 h rejects even placed
  orders, compensation uses inverted logic and its route is not subscribed.

## What differs (see spec "Deviations")

| Area | Original | Port | Why / effect |
|---|---|---|---|
| Database | MongoDB `order-database`, items embedded | Postgres tables `orders`, `order_items`, `order_item_discounts`, `order_compensations`, replicas `users`, `product_variants`, `tax_rates`, `coupons`, `shipment_methods` | uniform stack. An order is still read with one query and written with one atomic statement (data-modifying CTEs), so it keeps one round trip per document operation |
| Env vars | `MONGODB_URI` | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD` | Postgres |
| Health body | empty 200 | `{"status":"UP"}` 200 | actuator (status code and path unchanged) |
| Extra endpoints | none | `/dapr/config`, `/healthz` (Dapr SDK), GraphQL `GET /` queries, `/sdl` | harmless |
| GraphiQL | `GET /` HTML | not served | no client uses it |
| Root error response | `{"data":null,"errors":[…]}` | `{"errors":[…]}` (graphql-kotlin omits `data: null`) | same meaning for the gateway |
| `_entities` errors | message with locations/path | same message, without path | graphql-kotlin entity resolution |
| Panics (`todo!()` for a ProductItem entity, `remove(0)` on an empty `_entities`) | connection dropped | GraphQL error | a panic cannot be reproduced sensibly |
| `Int` > 2^31−1 | accepted (u32/u64 args; u64 outputs) | graphql-java `Int` is 32 bit (inputs rejected, outputs error) | values that large do not occur |
| Scalar parse error wording (UUID/Int) | async-graphql texts | graphql-java texts (except range errors on `first`/`skip`/`cvc`, which reuse async-graphql's texts) | |
| Metrics | axum-otel-metrics (HTTP server metrics via OTLP, 5 s) | OpenTelemetry Java agent (`OTEL_*`) | uniform stack |
