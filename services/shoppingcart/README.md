# shoppingcart

Shopping carts of the MiSArch users (one per user) and their items. Originally a Rust service (axum + async-graphql +
MongoDB); spec and every difference to the original: [docs/specs/shoppingcart.md](../../docs/specs/shoppingcart.md),
fixed defects: [docs/fixes/shoppingcart.md](../../docs/fixes/shoppingcart.md).

- GraphQL at `POST /` (gateway and order's `createOrder` call `/v1.0/invoke/shoppingcart/method/`), port 8080,
  `GET /health`, Dapr app id `shoppingcart`.
- Subscribes to `user/user/created`, `catalog/product-variant/created` (replicas) and `order/order/created` (removes
  the ordered items); publishes nothing.
- Env: `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, `OTEL_*`.

## Layout

```
event/                    ShoppingcartEvents (topics), EventController, model/ (event DTOs)
graphql/                  Query, Mutation, model/ (User, ShoppingCart, ShoppingCartItem, ProductVariant,
                          connection/ShoppingCartItemConnection), input/, dataloader/, federation/
persistence/              model/ (UserEntity, ShoppingCartItemEntity, ProductVariantEntity), repository/
service/                  ShoppingCartService, ShoppingCartItemService, UserService, ProductVariantService
```

Tests: `tools/build.sh :services:shoppingcart:test`; SDL: `tools/sdl-diff.sh shoppingcart` (empty).
