# return (Kotlin port)

Drop-in replacement of MiSArch's `return` service. It manages returns of delivered order items. It keeps local
replicas of orders and order items (from `order/order/created`) and of shipments (from `shipment/shipment/created` and
`shipment/shipment/status-updated`). It publishes `return/return/created`, which shipment consumes.

**Faithful defect:** the original never subscribes to `catalog/product-variant-version/created`. Its handler has no
`@Topic` or route, so the product-variant-version replica stays empty and every `createReturn` that passes the
earlier checks fails with `Product variant version not found`. The port reproduces this. The return flow therefore
never completes, the same as in the original MiSArch.

Original: `misarch/return` (Kotlin, Spring Boot 3.2, graphql-kotlin 7, R2DBC + QueryDSL, Dapr SDK 1.11).
The behavioural spec, quirks and deviations are in [docs/specs/return.md](../../docs/specs/return.md).
Package: ``org.misarch.`return` `` (the original used `org.misarch.returns`; the name is not observable outside).

| Contract | Value |
|---|---|
| Dapr app id | `return` |
| HTTP | port 8080: `POST /graphql`, `GET /health`, `GET /dapr/subscribe`, `GET /dapr/config`, `GET /healthz` |
| subscriptions | `order/order/created`, `shipment/shipment/created`, `shipment/shipment/status-updated`, each at `POST /subscription/<topic>`. **Not** `catalog/product-variant-version/created`, which returns 404 as in the original |
| publishes | `return/return/created` with payload `{"id","orderId","orderItemIds","reason","refundedAmount","createdAt"}`. `orderItemIds` is the list from the input, and `createdAt` is `ISO_OFFSET_DATE_TIME` |
| DB | PostgreSQL. The original Flyway migration `V1_1` is copied byte for byte (checksum -1517848369), including both triggers on `OrderItemEntity` |
| env | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, `OTEL_*`, `DAPR_HTTP_PORT` / `DAPR_HTTP_ENDPOINT` |

## Structure

The structure is the same as the original:
* `event/`: subscription controller, DTOs, and the dead PVV handler.
* `graphql/`:
  * `Query` and `Mutation` (`@Transactional(REQUIRES_NEW)`)
  * models `Return`, `Order`, `OrderItem`, `User`
  * connections: `ReturnConnection` (`INNER JOIN OrderEntity o`, NPE without header like the original), `OrderItemConnection` with `CommonOrder`
  * federation resolvers for `Order`, `OrderItem`, `User`. There is no resolver for `Return`, as in the original.
* `persistence/`: native inserts and the `ON CONFLICT` upserts, kept verbatim.
* `service/`: `ReturnService` keeps the original validation order and messages.

## Build and test

```
tools/build.sh :services:return:test   # 18 tests: 10 integration, 1 SDL, 7 unit
tools/sdl-diff.sh return               # empty against both the live SDL and misarch/schemas
tools/image.sh return                  # misarch-kotlin/return:dev (smoke-tested against Postgres 16)
```
