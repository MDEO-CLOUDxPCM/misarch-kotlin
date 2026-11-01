# shoppingcart

Shopping carts of the users (one per user) and their items; `order/order/created` removes the ordered items.
Original: `misarch/shoppingcart` (Rust, axum, async-graphql, MongoDB). Spec and deviations:
[docs/specs/shoppingcart.md](../../docs/specs/shoppingcart.md), fixed defects:
[docs/fixes/shoppingcart.md](../../docs/fixes/shoppingcart.md).

| Contract | Value |
|---|---|
| Dapr app id | `shoppingcart` |
| HTTP | port 8080: GraphQL `POST /` (gateway: `/v1.0/invoke/shoppingcart/method/`, also called by order's `createOrder`), `GET /health`, `GET /dapr/subscribe` |
| subscriptions | `user/user/created`, `catalog/product-variant/created`, `order/order/created`, each at `POST /subscription/<topic>` |
| publishes | nothing |
| DB | PostgreSQL, tables `ShoppingCartItemEntity`; replicas `UserEntity`, `ProductVariantEntity` |
| env | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, `OTEL_*`, `DAPR_HTTP_PORT` / `DAPR_HTTP_ENDPOINT` |

## Layout

```
ShoppingcartApplication.kt  Spring Boot entry point
event/                      ShoppingcartEvents (topics), EventController (subscriptions), model/ (event DTOs)
graphql/                    Query, Mutation, model/ (User, ShoppingCart, ShoppingCartItem, ProductVariant,
                            connection/), input/, dataloader/, federation/
persistence/                model/ (entities), repository/
service/                    ShoppingCartService, ShoppingCartItemService, UserService, ProductVariantService
```

## Build and test

```
tools/build.sh :services:shoppingcart:test
tools/sdl-diff.sh shoppingcart            # empty
tools/image.sh shoppingcart               # misarch-kotlin/shoppingcart:dev
```
