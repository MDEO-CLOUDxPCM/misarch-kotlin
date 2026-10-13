# review (Kotlin port)

Port of the MiSArch review service (original: Rust, axum + async-graphql + MongoDB). Spec, quirks and test plan:
[docs/specs/review.md](../../docs/specs/review.md).

Drop-in contract (unchanged): Dapr app id `review`, port 8080, GraphQL at `POST /` (GraphiQL at `GET /`), `GET /health`,
`GET /dapr/subscribe` (exact original JSON), event routes `/on-topic-event`, `/on-product-variant-creation-event`,
federation SDL = `reference/sdl/review.graphql` (live SDL of the original; the older `misarch/schemas` copy that the
gateway composes declares `review: Review!`, `averageRating: Float!` and `Review @shareable`).

Defects fixed since tag `faithful` ([docs/fixes/review.md](../../docs/fixes/review.md)): product-variant events
on the subscribed route are stored (D1), so `createReview` works and `_entities(ProductVariant)` resolves; replica
events are idempotent; `FOUR_STARS` is stored as `FourStars`; `averageRating` covers all visible reviews; no skip
off-by-one; `RATING` sorts by stars and `CREATED_AT` by `created_at`; one review per (user, variant) enforced by a
unique index. Kept: default page size 25, `_id: -1` tiebreak, `f32` average, `{"status":0}` event answers, Rust type
names in errors.

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
