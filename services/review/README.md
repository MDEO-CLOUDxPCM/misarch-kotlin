# review

Reviews of product variants (one per user and variant, 1-5 stars) and the average ratings of products and product
variants. Originally a Rust service (axum + async-graphql + MongoDB); spec and every difference to the original:
[docs/specs/review.md](../../docs/specs/review.md), fixed defects: [docs/fixes/review.md](../../docs/fixes/review.md).

- GraphQL at `POST /` (gateway: `/v1.0/invoke/review/method/`), port 8080, `GET /health`, Dapr app id `review`.
- Subscribes to `user/user/created`, `catalog/product/created`, `catalog/product-variant/created` (replicas);
  publishes nothing. The live SDL is `reference/sdl/review.graphql`; the older `misarch/schemas` copy that the gateway
  composes declares `review: Review!`, `averageRating: Float!` and `Review @shareable`.
- Env: `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, `OTEL_*`.

## Layout

```
event/                    ReviewEvents (topics), EventController, model/ (event DTOs)
graphql/                  Query, Mutation, model/ (Review, Rating, User, Product, ProductVariant,
                          connection/ReviewConnection), input/, dataloader/, federation/
persistence/              model/ (ReviewEntity, UserEntity, ProductEntity, ProductVariantEntity), repository/
service/                  ReviewService, UserService, ProductService, ProductVariantService
```

Tests: `tools/build.sh :services:review:test`; SDL: `tools/sdl-diff.sh review` (empty).
