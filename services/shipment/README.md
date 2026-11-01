# shipment

Shipment methods and shipments. It computes shipping fees for `order` (`calculateShipmentFees`). On
`payment/payment/payment-enabled` it creates one shipment per shipment method of the order and registers it with the
external shipment provider (the `simulation` service), which reports status changes back; on `return/return/created`
it creates a return shipment. Original: `misarch/shipment` (Kotlin, Spring Boot 3.2, graphql-kotlin 7, R2DBC +
QueryDSL). Spec and deviations: [docs/specs/shipment.md](../../docs/specs/shipment.md), fixed defects:
[docs/fixes/shipment.md](../../docs/fixes/shipment.md).

| Contract | Value |
|---|---|
| Dapr app id | `shipment` |
| HTTP | port 8080: GraphQL `POST /graphql`, `GET /health`, `GET /dapr/subscribe`; provider callback `POST /shipment/{id}/status`; experiment config `GET /ecs/defined-variables`, `POST /ecs/variables` (`providerRetries`, default 3; `providerRetryDelay`, default 500 ms) |
| subscriptions | `address/user-address/created`, `address/vendor-address/created`, `catalog/product-variant-version/created`, `payment/payment/payment-enabled`, `return/return/created`, each at `POST /subscription/<topic>` |
| publishes | `shipment/shipment/created`, `shipment/shipment/creation-failed`, `shipment/shipment/status-updated`, `shipment/shipment-method/created`, `shipment/shipment-method/archived` |
| invokes | the shipment provider: direct HTTP `POST` to `MISARCH_SHIPMENT_PROVIDER_ENDPOINT`, `providerRetries` retries `providerRetryDelay` ms apart |
| DB | PostgreSQL, tables `ShipmentMethodEntity`, `ShipmentEntity`, `ShipmentToOrderItemEntity`; replicas `AddressEntity` (user and vendor addresses), `ProductVariantVersionEntity`, `OrderItemEntity` |
| env | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, `OTEL_*`, `DAPR_HTTP_PORT` / `DAPR_HTTP_ENDPOINT`, **`MISARCH_SHIPMENT_PROVIDER_ENDPOINT` (required, no default)** |

## Layout

```
ShipmentApplication.kt      Spring Boot entry point
ecs/                        ExperimentConfigService (the experiment-config variables)
event/                      ShipmentEvents (topics), EventController (subscriptions), model/ (event DTOs,
                            model/order/: the order DTOs of the payment-enabled payload)
graphql/                    Query, Mutation, model/ (Shipment, ShipmentMethod, ShipmentStatus, Address, UserAddress,
                            VendorAddress, Order, OrderItem, Return, connection/), input/, dataloader/, federation/
persistence/                model/ (entities), repository/
provider/                   ShipmentProviderController (status callback), configuration properties, the shipment
                            definition sent to the provider
service/                    ShipmentService, ShipmentMethodService, AddressService, ProductVariantVersionService
```

## Build and test

```
tools/build.sh :services:shipment:test
tools/sdl-diff.sh shipment                # empty
tools/image.sh shipment                   # misarch-kotlin/shipment:dev
```
