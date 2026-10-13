# review (Kotlin port)

Port of the MiSArch review service (original: Rust, axum + async-graphql + MongoDB). Spec, quirks and test plan:
[docs/specs/review.md](../../docs/specs/review.md).

Drop-in contract (unchanged): Dapr app id `review`, port 8080, GraphQL at `POST /` (GraphiQL at `GET /`), `GET /health`,
`GET /dapr/subscribe` (exact original JSON), event routes `/on-topic-event`, `/on-product-variant-creation-event`,
federation SDL = `reference/sdl/review.graphql` (live SDL of the original; the older `misarch/schemas` copy that the
gateway composes declares `review: Review!`, `averageRating: Float!` and `Review @shareable`).

Reproduced on purpose (original defects/quirks): product-variant events answered 500 on the subscribed route (the
replica stays empty, so `createReview` always fails and `_entities(ProductVariant)` is `[null]`); `FOUR_STARS` stored
as `FourStarst` by `updateReview` (review becomes unreadable, lists answer HTTP 500); default page size 25, skip
off-by-one, `_id: -1` tiebreak, lexicographic rating sort, `CREATED_AT` sorting by `last_updated_at`;
`averageRating` in `f32` over the first 25 reviews only; `{"status":0}` event answers; Rust type names in errors.

## What differs from the original

| Difference | Why | Effect |
|---|---|---|
| Postgres (R2DBC, Flyway) instead of MongoDB: tables `users`, `products`, `product_variants`, `reviews` (embedded user/variant flattened to columns) | uniform stack | env `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD` replace `MONGODB_URI` |
| A Rust panic (unreadable review inside a fetched page) aborts the TCP connection in the original; the port answers HTTP 500 with an empty body | not reproducible in WebFlux; the gateway sees HTTP 500 from Dapr in both cases | none for callers |
| `GET /health` answers `{"status":"UP"}` (original: empty 200) | actuator (PORTING.md §7) | none (health checks use the status) |
| `GET /dapr/config` and `/healthz` exist (Dapr Spring Boot integration); original: 404 | shared stack | Dapr treats both as "no config" |
| Scalar coercion error texts of graphql-java for malformed `UUID`/`Int` literals in some positions; `Int` values above 2^31-1 rejected | graphql-java validation | only for invalid requests |
| Metrics via the OpenTelemetry Java agent (`OTEL_*` env vars) instead of `axum-otel-metrics` | shared image | metric names differ |

Rust-compat helpers used: `org.misarch.common.rust` (libs/common, see docs/PORTING.md §11).
