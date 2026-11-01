# wishlist

Wishlists of the MiSArch users (a name and a set of product variants). Originally a Rust service (axum +
async-graphql + MongoDB); spec and every difference to the original: [docs/specs/wishlist.md](../../docs/specs/wishlist.md),
fixed defects: [docs/fixes/wishlist.md](../../docs/fixes/wishlist.md).

- GraphQL at `POST /` (gateway: `/v1.0/invoke/wishlist/method/`), port 8080, `GET /health`, Dapr app id `wishlist`.
- Subscribes to `user/user/created` and `catalog/product-variant/created` (replicas); publishes nothing.
- Env: `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, `OTEL_*`.

## Layout

```
event/                    WishlistEvents (topics), EventController, model/ (event DTOs)
graphql/                  Query, Mutation, model/ (Wishlist, User, ProductVariant, connection/), input/,
                          dataloader/, federation/
persistence/              model/ (WishlistEntity, WishlistToProductVariantEntity + Key, UserEntity,
                          ProductVariantEntity), repository/
service/                  WishlistService, UserService, ProductVariantService
```

Tests: `tools/build.sh :services:wishlist:test`; SDL: `tools/sdl-diff.sh wishlist` (empty).
