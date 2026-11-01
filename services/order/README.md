# order (Kotlin port)

Drop-in replacement of MiSArch's `order` service. It owns orders and their items, creates them from the user's
shopping cart (`createOrder`: synchronous calls to shoppingcart, inventory, discount and shipment) and starts the buy
saga by publishing `order/order/created` (`placeOrder`). A failed shipment compensates the order's items.

Original: `misarch/order` (Rust, axum, async-graphql, MongoDB). The behaviour of the original, the fixed defects and
every visible deviation are in [docs/specs/order.md](../../docs/specs/order.md) and
[docs/fixes/order.md](../../docs/fixes/order.md).

| Contract | Value |
|---|---|
| Dapr app id | `order` |
| HTTP | port 8080: GraphQL `POST /` (gateway: `/v1.0/invoke/order/method/`), `GET /health`, `GET /dapr/subscribe`, `GET /dapr/config`, `GET /healthz` |
| subscriptions | `catalog/product-variant/updated`, `catalog/product-variant-version/created`, `discount/coupon/created`, `tax/tax-rate-version/created`, `shipment/shipment-method/created`, `user/user/created`, `address/user-address/created`, `address/user-address/archived`, `shipment/shipment/creation-failed`. Each one is served at `POST /subscription/<topic>` |
| publishes | `order/order/created` (null fields written, millisecond timestamps with `Z`, golden test against the original's event), `order/order-compensation/created` |
| invokes | shoppingcart (`/`, with the caller's `Authorized-User`), inventory, discount, shipment (`/graphql`), query documents in `src/main/resources/queries` |
| DB | PostgreSQL, tables `OrderEntity`, `OrderItemEntity`, `OrderItemToDiscountEntity`, `OrderCompensationEntity`, `OrderCompensationToOrderItemEntity` and the replicas `UserEntity`, `UserAddressEntity`, `CouponEntity`, `ShipmentMethodEntity`, `ProductVariantEntity`, `TaxRateEntity` |
| env | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, `OTEL_*`, `DAPR_HTTP_PORT` / `DAPR_HTTP_ENDPOINT` |

## Structure

* `event/`: topics, the `@Topic` controller and the event DTOs.
* `graphql/`: `Query`, `Mutation`, `model/`, `model/connection/` (order's own `CommonOrderInput`/`OrderDirection`,
  whose SDL descriptions differ from libs/common's), `input/`, `dataloader/`, `federation/`, the scalars without
  descriptions.
* `persistence/`: entities (link tables with composite keys) and `CoroutineCrudRepository`s.
* `remote/`: `CheckoutClient` (the four invocations through `DaprServiceInvoker`) and its DTOs.
* `service/`: `CheckoutService` (validation, invocations, pricing; no transaction around the invocations),
  `OrderService` (the write transactions: create, place with its event, reject), `OrderCompensationService` and the
  replica services.

## Build and test

```
tools/build.sh :services:order:test     # integration (Testcontainers Postgres + fake Dapr), statements, migrations, payloads, SDL
tools/sdl-diff.sh order                 # empty
```

## History

| Version | What |
|---|---|
| tag `faithful` | port reproducing the Rust original including its defects |
| docs/fixes/order.md | defects fixed (amounts, cvc key, visibility, addresses, orderItem, idempotency, compensation, placement CAS, paging) |
| relational read path | items and discounts as child tables read through connections |
| Kotlin conventions | Spring Data entities/repositories, `@Topic` controller, libs/common publisher and invoker, Kotlin table names (`V4__kotlin_naming.sql`) |
