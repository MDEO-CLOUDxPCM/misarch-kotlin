# inventory

Product items in stock per product variant: creation, reservation for orders and the status of reserved items along
the buy saga (payment, shipment); `order` reads the number of available items of a product variant
(`_entities(ProductVariant){inventoryCount}`, no `Authorized-User` needed). Original: `misarch/inventory`
(TypeScript, NestJS, MongoDB). Spec and deviations: [docs/specs/inventory.md](../../docs/specs/inventory.md), fixed
defects: [docs/fixes/inventory.md](../../docs/fixes/inventory.md).

| Contract | Value |
|---|---|
| Dapr app id | `inventory` |
| HTTP | port 8080: GraphQL `POST /graphql`, `GET /health`, `GET /dapr/subscribe` |
| subscriptions | `catalog/product-variant/created`, `order/order/created`, `payment/payment/payment-enabled`, `payment/payment/payment-failed`, `shipment/shipment/created`, `shipment/shipment/status-updated`, `discount/order/validation-failed`, each at `POST /subscription/<topic>` |
| publishes | `inventory/product-item/reservation-succeeded`, `inventory/product-item/reservation-failed` (`{"order": <order as received>[, "failedProductVariantIds"]}`) |
| DB | PostgreSQL, tables `ProductItemEntity`; replicas `ProductVariantEntity` |
| env | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, `OTEL_*`, `DAPR_HTTP_PORT` / `DAPR_HTTP_ENDPOINT` |

## Layout

```
InventoryApplication.kt     Spring Boot entry point
event/                      InventoryEvents (topics), EventController (subscriptions), model/ (event DTOs)
graphql/                    Query, Mutation, model/ (ProductItem, ProductItemStatus, ProductVariant, connection/),
                            input/, dataloader/, federation/
persistence/                model/ (entities), repository/
service/                    ProductItemService, ReservationService, ProductVariantService
```

## Build and test

```
tools/build.sh :services:inventory:test
tools/sdl-diff.sh inventory               # empty
tools/image.sh inventory                  # misarch-kotlin/inventory:dev
```

`tools/sdl-diff.sh inventory --schemas` compares against the `misarch/schemas` copy that the gateway composes; it
differs only by the transform-added `@shareable` on `ProductItemConnection`.
