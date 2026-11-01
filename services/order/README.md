# order

Orders and their items. `createOrder` builds an order from the user's shopping cart (synchronous calls to
shoppingcart, inventory, discount and shipment), `placeOrder` starts the buy saga by publishing `order/order/created`,
and a failed shipment compensates the order's items. Original: `misarch/order` (Rust, axum, async-graphql, MongoDB).
Spec and deviations: [docs/specs/order.md](../../docs/specs/order.md), fixed defects:
[docs/fixes/order.md](../../docs/fixes/order.md).

| Contract | Value |
|---|---|
| Dapr app id | `order` |
| HTTP | port 8080: GraphQL `POST /` (gateway: `/v1.0/invoke/order/method/`), `GET /health`, `GET /dapr/subscribe` |
| subscriptions | `catalog/product-variant/updated`, `catalog/product-variant-version/created`, `discount/coupon/created`, `tax/tax-rate-version/created`, `shipment/shipment-method/created`, `user/user/created`, `address/user-address/created`, `address/user-address/archived`, `shipment/shipment/creation-failed`, each at `POST /subscription/<topic>` |
| publishes | `order/order/created`, `order/order-compensation/created` |
| invokes | through Dapr: shoppingcart (`POST /`, with the caller's `Authorized-User`), inventory, discount and shipment (`POST /graphql`); the query documents are in `src/main/resources/queries` |
| DB | PostgreSQL, tables `OrderEntity`, `OrderItemEntity`, `OrderItemToDiscountEntity`, `OrderCompensationEntity`, `OrderCompensationToOrderItemEntity`; replicas `UserEntity`, `UserAddressEntity`, `CouponEntity`, `ShipmentMethodEntity`, `ProductVariantEntity`, `TaxRateEntity` |
| env | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, `OTEL_*`, `DAPR_HTTP_PORT` / `DAPR_HTTP_ENDPOINT` |

## Layout

```
OrderApplication.kt         Spring Boot entry point
event/                      OrderEvents (topics), EventController (subscriptions), model/ (event DTOs)
graphql/                    Query, Mutation, model/ (Order, OrderItem, OrderStatus, RejectionReason, Discount,
                            ProductVariant, ProductVariantVersion, ShipmentMethod, ShoppingCartItem, TaxRateVersion,
                            User, UserAddress, connection/), input/, dataloader/, federation/
persistence/                model/ (entities), repository/
remote/                     CheckoutClient (the four invocations through DaprServiceInvoker), model/ (their DTOs)
service/                    CheckoutService (validation, invocations, pricing), OrderService (create, place with its
                            event, reject), OrderCompensationService, UserService, CouponService,
                            ShipmentMethodService, ProductVariantService, TaxRateService
```

## Build and test

```
tools/build.sh :services:order:test
tools/sdl-diff.sh order                   # empty
tools/image.sh order                      # misarch-kotlin/order:dev
```
