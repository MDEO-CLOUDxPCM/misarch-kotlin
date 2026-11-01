# wishlist

Wishlists of the users (a name and a set of product variants). Original: `misarch/wishlist` (Rust, axum,
async-graphql, MongoDB). Spec and deviations: [docs/specs/wishlist.md](../../docs/specs/wishlist.md), fixed defects:
[docs/fixes/wishlist.md](../../docs/fixes/wishlist.md).

| Contract | Value |
|---|---|
| Dapr app id | `wishlist` |
| HTTP | port 8080: GraphQL `POST /` (gateway: `/v1.0/invoke/wishlist/method/`), `GET /health`, `GET /dapr/subscribe` |
| subscriptions | `user/user/created`, `catalog/product-variant/created`, each at `POST /subscription/<topic>` |
| publishes | nothing |
| DB | PostgreSQL, tables `WishlistEntity`, `WishlistToProductVariantEntity`; replicas `UserEntity`, `ProductVariantEntity` |
| env | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, `OTEL_*`, `DAPR_HTTP_PORT` / `DAPR_HTTP_ENDPOINT` |

## Layout

```
WishlistApplication.kt      Spring Boot entry point
event/                      WishlistEvents (topics), EventController (subscriptions), model/ (event DTOs)
graphql/                    Query, Mutation, model/ (Wishlist, User, ProductVariant, connection/), input/,
                            dataloader/, federation/
persistence/                model/ (entities), repository/
service/                    WishlistService, UserService, ProductVariantService
```

## Build and test

```
tools/build.sh :services:wishlist:test
tools/sdl-diff.sh wishlist                # empty
tools/image.sh wishlist                   # misarch-kotlin/wishlist:dev
```
