# return

Returns of delivered order items. It replicates orders and order items (from `order/order/created`), product
variant versions and shipments (from `shipment/shipment/created` and `shipment/shipment/status-updated`), and
publishes `return/return/created`, which shipment consumes. Original: `misarch/return` (Kotlin, Spring Boot 3.2,
graphql-kotlin 7, R2DBC + QueryDSL). Spec and deviations: [docs/specs/return.md](../../docs/specs/return.md), fixed
defects: [docs/fixes/return.md](../../docs/fixes/return.md).

| Contract | Value |
|---|---|
| Dapr app id | `return` |
| HTTP | port 8080: GraphQL `POST /graphql`, `GET /health`, `GET /dapr/subscribe` |
| subscriptions | `order/order/created`, `shipment/shipment/created`, `shipment/shipment/status-updated`, `catalog/product-variant-version/created`, each at `POST /subscription/<topic>` |
| publishes | `return/return/created` |
| DB | PostgreSQL, tables `ReturnEntity`; replicas `OrderEntity`, `OrderItemEntity`, `ProductVariantVersionEntity`, `ShipmentEntity` |
| env | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, `OTEL_*`, `DAPR_HTTP_PORT` / `DAPR_HTTP_ENDPOINT` |

## Layout

The package is ``org.misarch.`return` `` (`return` is a Kotlin keyword).

```
ReturnApplication.kt        Spring Boot entry point
event/                      ReturnEvents (topics), EventController (subscriptions), model/ (event DTOs)
graphql/                    Query, Mutation, model/ (Return, Order, OrderItem, User, connection/), input/,
                            dataloader/, federation/
persistence/                model/ (entities), repository/
service/                    ReturnService, OrderService, ShipmentService, ProductVariantVersionService
```

## Build and test

```
tools/build.sh :services:return:test
tools/sdl-diff.sh return                  # empty
tools/image.sh return                     # misarch-kotlin/return:dev
```
