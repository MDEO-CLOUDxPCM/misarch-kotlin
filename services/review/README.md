# review

Reviews of product variants (one per user and variant, 1-5 stars) and the average ratings of products and product
variants. Original: `misarch/review` (Rust, axum, async-graphql, MongoDB). Spec and deviations:
[docs/specs/review.md](../../docs/specs/review.md), fixed defects: [docs/fixes/review.md](../../docs/fixes/review.md).

| Contract | Value |
|---|---|
| Dapr app id | `review` |
| HTTP | port 8080: GraphQL `POST /` (gateway: `/v1.0/invoke/review/method/`), `GET /health`, `GET /dapr/subscribe` |
| subscriptions | `user/user/created`, `catalog/product/created`, `catalog/product-variant/created`, each at `POST /subscription/<topic>` |
| publishes | nothing |
| DB | PostgreSQL, tables `ReviewEntity`; replicas `UserEntity`, `ProductEntity`, `ProductVariantEntity` |
| env | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, `OTEL_*`, `DAPR_HTTP_PORT` / `DAPR_HTTP_ENDPOINT` |

## Layout

```
ReviewApplication.kt        Spring Boot entry point
event/                      ReviewEvents (topics), EventController (subscriptions), model/ (event DTOs)
graphql/                    Query, Mutation, model/ (Review, Rating, User, Product, ProductVariant, connection/),
                            input/, dataloader/, federation/
persistence/                model/ (entities), repository/
service/                    ReviewService, UserService, ProductService, ProductVariantService
```

## Build and test

```
tools/build.sh :services:review:test
tools/sdl-diff.sh review                  # empty
tools/image.sh review                     # misarch-kotlin/review:dev
```

`tools/sdl-diff.sh review --schemas` compares against the older `misarch/schemas` copy that the gateway composes;
it differs in `review: Review!`, `averageRating: Float!` and `Review @shareable`.
