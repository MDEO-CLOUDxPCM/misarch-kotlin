# Port spec: `review`

Original: `/home/devbox/workspace/misarch/review` (Rust 2024 edition, axum 0.8.3, async-graphql 7.0.16,
mongodb driver 2.8, `mongodb-cursor-pagination` 0.3.2, bson 2.14). Crate `misarch-review`
(Rust ident `misarch_review`; this shows up in error strings, see §9).
Reference SDL: `reference/sdl/review.graphql` (captured from the running original's `_service { sdl }`). It matches the
current code. The schemas repo copy (`misarch/schemas/review.graphql`, which the gateway composes) is **older** and differs
in 3 places (§3.1).

The code is the truth. The docs (`docs/.../services/review.mdx`) list only `user/user/created` and
`catalog/product-variant/created`. The code also subscribes `catalog/product/created`.

## 1. Role in the system

- Owns `Review` (one per user and product variant, rating 1–5 stars, body, visibility).
- Holds id-only replicas of User, Product and ProductVariant (`productId` kept), fed by events.
- Contributes `Product.reviews/averageRating`, `ProductVariant.reviews/averageRating` and `User.reviews`
  to the federated graph.
- Publishes nothing and makes no outgoing calls.
- On the workload's critical path: Gatling's `product(id){ defaultVariant{ averageRating } }` makes the
  gateway call `_entities(ProductVariant)` here on every buy iteration.
- **In the deployed system the product-variant replica is never filled** (§6.2, defect D1). As a result
  `createReview` always fails, there are no reviews, and `_entities(ProductVariant)` always returns `null`.

## 2. Runtime contract (must stay identical)

| Item | Value |
|---|---|
| HTTP port | 8080 (bind `0.0.0.0:8080`, hard-coded) |
| GraphQL | `POST /` (gateway `routing_url: http://localhost:3500/v1.0/invoke/review/method/`). `GET /` serves GraphiQL HTML (endpoint `/`). Only single requests: a JSON array body (batch) → HTTP 400. Content types `application/json` and `multipart/form-data`. The response is always HTTP 200 `application/json` (except for the panic case in §5.7). |
| Health | `GET /health` → `200`, empty body. Docker healthcheck `wget -qO - http://localhost:8080/health` |
| Dapr app-id | `review` (app-port `${EXPERIMENT_CONFIG_SIDECAR_PORT:-8080}`, app-protocol http, Dapr HTTP port 3500) |
| Dapr endpoints served | `GET /dapr/subscribe`, `POST /on-topic-event`, `POST /on-product-variant-creation-event` (no `/dapr/config`, no `/healthz`) |
| Dapr endpoints called | none |
| Pub/sub component | `pubsub` |
| Env vars | `MONGODB_URI` (required; the process panics when it is unset; DB `review-database`, app name `Review`) → replaced by the Postgres variables of docs/PORTING.md. `OTEL_EXPORTER_OTLP_ENDPOINT` (default `http://localhost:4318`; HTTP server metrics are pushed to `<endpoint>/v1/metrics` every 5 s, service name `review`). |
| CLI | `--generate-schema` writes `./schemas/review.graphql` and exits (build-time tool only). |
| Auth input | Header `Authorized-User: {"id":"<uuid>","roles":["buyer"|"employee"|"admin",…]}`, set by the gateway |

## 3. GraphQL schema

Federation v2 with `@key`/`@shareable`. Scalars: `UUID`, `DateTime`, `_Any`, `FieldSet` (+ `link__Import`).

- `Query.reviews(first: Int, skip: Int, orderBy: ReviewOrderInput): ReviewConnection!`
- `Query.review(id: UUID!): Review` (nullable)
- `Query._entities(representations: [_Any!]!): [_Entity]!`, `union _Entity = Product | ProductVariant | User`
- `Mutation.createReview(input: CreateReviewInput!): Review!`
- `Mutation.updateReview(input: UpdateReviewInput!): Review!`
- `Mutation.deleteReview(id: UUID!): Boolean!`
- `type Product @key(fields: "id")`: `id: UUID!`, `reviews(first, skip, orderBy): ReviewConnection!`, `averageRating: Float` (nullable)
- `type ProductVariant @key(fields: "id")`: same fields as Product (the replica's `productId` is not exposed)
- `type User @key(fields: "id")`: `id: UUID!`, `reviews(first, skip, orderBy): ReviewConnection!`
- `type Review` (no directive): `id: UUID!`, `user: User!`, `productVariant: ProductVariant!`,
  `body: String!`, `rating: Rating!`, `createdAt: DateTime!`, `lastUpdatedAt: DateTime!`, `isVisible: Boolean!`
- `type ReviewConnection @shareable`: `nodes: [Review!]!`, `hasNextPage: Boolean!`, `totalCount: Int!`
- `enum Rating {ONE_STARS, TWO_STARS, THREE_STARS, FOUR_STARS, FIVE_STARS}`
- `enum OrderDirection {ASC, DESC}`, `enum ReviewOrderField {ID, USER_ID, PRODUCT_VARIANT, RATING, CREATED_AT}`
- `input ReviewOrderInput {direction: OrderDirection, field: ReviewOrderField}`
- `input CreateReviewInput {userId: UUID!, productVariantId: UUID!, body: String!, rating: Rating!, isVisible: Boolean}`
- `input UpdateReviewInput {id: UUID!, body: String, rating: Rating, isVisible: Boolean}`
- All descriptions as in the reference SDL (they are identical in the code).

### 3.1 Gateway's schema copy vs current code

| Element | `misarch/schemas/review.graphql` (gateway input) | Current code = `reference/sdl/review.graphql` |
|---|---|---|
| `Query.review` | `Review!` | `Review` (`Result<Option<Review>>`) |
| `Product.averageRating`, `ProductVariant.averageRating` | `Float!` | `Float` (`Option<f32>`) |
| `type Review` | `@shareable` | no directive |

The gateway composes its supergraph statically from `schemas/*.graphql` at build time and never reads `_service`
at runtime. So the gateway believes the non-null variants, while the subgraph answers `null` (`review(id)` for an
unknown id, `averageRating` without visible reviews). The port follows the code and `reference/sdl`, so it sends the
same JSON as the original and the gateway sees no difference.

### 3.2 Scalars and value formats

| Scalar | Output | Input |
|---|---|---|
| `UUID` | lowercase hyphenated | uuid crate `parse_str`: hyphenated, simple (32 hex), `urn:uuid:` and `{…}` forms, any case. Error: `Failed to parse "UUID": <uuid error>` |
| `DateTime` | chrono `to_rfc3339()`: `SecondsFormat::AutoSi` with `+00:00`. Values have ms precision, so `2026-10-13T12:00:00.123+00:00`, or `2026-10-13T12:00:00+00:00` when millis = 0 | not used as input |
| `Float` (`averageRating`) | computed as `f32`, emitted as `(f32 as f64)`. So 13/3 → `4.333333492279053`, 4.5 → `4.5`, 5 → `5.0` | – |
| `Int` args `first` (u32), `skip` (u64) | – | negative → `Failed to parse "Int": Invalid number` (`as_u64()` fails); above u32 max → `Failed to parse "Int": Only integers from 0 to 4294967295 are accepted.`. async-graphql accepts values above 2^31-1 for these; graphql-java's `Int` does not (deviation, unreachable in practice). |

## 4. Authorization

`Authorized-User` is parsed per request with serde: `{"id": <uuid string>, "roles": [<"buyer"|"admin"|"employee">…]}`.
Unknown extra keys are ignored. A missing `roles`, an unknown role string, a bad uuid or invalid JSON makes the
header count as **absent**; this is not an error by itself.

`authorize_user(id?)`:
- no (parsable) header → error `Authentication failed. Authorized-User header is not set or could not be parsed.`
- any role `admin` or `employee` → OK
- else, header id == `id` → OK
- else → error ``Authentication failed for user of UUID: `<header id>`. Operation not permitted.``

| Operation | Auth |
|---|---|
| `createReview` | `authorize_user(input.userId)` **first**, before any validation |
| `updateReview`, `deleteReview` | load the review first (not found → not-found error even without a header), then `authorize_user(review.user.id)` |
| all queries, entity resolvers, nested fields | none |

## 5. Resolvers (exact semantics)

Messages are `errors[].message`. A failing non-null root field gives `data: null`.
`<RT>` = `misarch_review::graphql::model::review::Review`, `<UT>` = `misarch_review::graphql::model::user::User`.

### 5.1 `createReview(input)`
1. `authorize_user(input.userId)`.
2. Product variant `input.productVariantId` must be in `product_variants`, else
   ``Product variant with the UUID: `<pvId>` is not present in the system.``
3. User `input.userId` must be in `users`, else ``<UT> with UUID: `<userId>` not found.``
4. Load the product variant again (to copy `{id, productId}` into the review).
5. `now` = current time truncated to ms. Review = `{id: new v4, user: {id: userId}, productVariant: {id, productId},
   body, rating, createdAt: now, lastUpdatedAt: now, isVisible: input.isVisible ?? true}`.
6. Duplicate check: any review with the same `productVariant.id` and `user.id` → error
   ``User of UUID: `<userId>` has already written a review for product variant of UUID: `<pvId>`.``
   (also when the existing review is undeserializable, §5.8). There is no unique index: concurrent creates can both succeed.
7. Insert (failure → `Adding review failed in MongoDB.`), then re-read by id (§5.8 rules) and return it.
   The body is not validated (empty string allowed).

### 5.2 `updateReview(input)`
1. `now` (ms) is taken first.
2. Load review `input.id` (missing or undeserializable → ``<RT> with UUID: `<id>` not found.``).
3. `authorize_user(review.user.id)`.
4. Up to three separate updates, in this order, each only if the input field is non-null:
   `body` + `lastUpdatedAt=now`; `rating` + `lastUpdatedAt=now`; `isVisible` + `lastUpdatedAt=now`.
   Failure messages: ``Updating body of review of id: `<id>` failed in MongoDB.`` (`rating`/`visibility` likewise).
   They are not transactional; an earlier update stays applied when a later one fails.
5. Re-read and return (missing/undeserializable → ``<RT> with UUID: `<id>` not found.``).
   With no field set, nothing changes (`lastUpdatedAt` stays).
- **QUIRK Q1 (FourStarst)**: the update path stores ratings through a hand-written `to_string()`:
  `OneStars`, `TwoStars`, `ThreeStars`, **`FourStarst`**, `FiveStars`. The insert path stores serde names
  (`FourStars`). After `updateReview(rating: FOUR_STARS)` the stored value is `FourStarst`. The update is applied,
  but the re-read fails, so the mutation returns the not-found error. From then on the review is undeserializable (§5.8).

### 5.3 `deleteReview(id)`
Load (missing/undeserializable → not-found error) → `authorize_user(review.user.id)` → delete
(failure → ``Deleting review of id: `<id>` failed in MongoDB.``) → `true`.

### 5.4 `review(id)`
No auth. Unknown id → `null`. Undeserializable → error ``<RT> with UUID: `<id>` not found.`` (`data.review = null`
plus the error).

### 5.5 Connections: `reviews`, `Product.reviews`, `ProductVariant.reviews`, `User.reviews`
Filters: none / `productVariant.productId = product.id` / `productVariant.id = pv.id` / `user.id = user.id`.
Semantics of `mongodb-cursor-pagination` 0.3.2 without cursors (reproduce exactly):
```
L    = first ?? 25                       # DEFAULT_LIMIT 25 when `first` is absent
dir  = orderBy.direction ?? ASC ; f = orderBy.field ?? ID
sort = [f dir] ++ (f == ID ? [] : [id DESC])   # library appends _id:-1 as tiebreak
total = count(filter)                    # ignores skip/first
if total == 0: return {nodes: [], hasNextPage: false, totalCount: 0}
if skip in (null, 0):
    rows = find(filter, sort, limit L+1)
    hasMore = len(rows) > L
else:
    rows = find(filter, sort, offset skip, limit L+1)
    hasMore = len(rows) + skip < total
if hasMore: drop the last row
return {nodes: rows, hasNextPage: hasMore, totalCount: total}
```
- **QUIRK Q2 (off-by-one)**: with `skip > 0` and exactly `L+1` rows remaining, `L+1` nodes are returned
  and `hasNextPage = false`.
- `first: 0` → limit 1 → at most 0 nodes; `hasNextPage` true if any row matched.
- **QUIRK Q3 (sort keys)**, the field → Mongo key mapping in `ReviewOrderField::as_str`:

| GraphQL field | Mongo key | Port ORDER BY (dir = requested direction) |
|---|---|---|
| `ID` | `_id` | `id dir` |
| `USER_ID` | `user` (embedded `{_id}`) | `user_id dir, id DESC` |
| `PRODUCT_VARIANT` | `product_variant` (embedded `{_id, product_id}`) | `product_variant_id dir, product_variant_product_id dir, id DESC` |
| `RATING` | `rating` (the **stored string**) | `rating COLLATE "C" dir, id DESC`. The order is lexicographic, not numeric: `FiveStars < FourStars < FourStarst < OneStars < ThreeStars < TwoStars` |
| `CREATED_AT` | **`last_updated_at`** | `last_updated_at dir, id DESC` |

  UUIDs (BSON binary subtype 4) compare byte-wise. Postgres `uuid` ordering is also byte-wise (memcmp),
  but `java.util.UUID.compareTo` is not (signed longs), so in-memory sorting must use an unsigned byte comparator.

### 5.6 `averageRating` (Product, ProductVariant)
Calls the connection of §5.5 with `first/skip/orderBy` all absent, which gives **at most the first 25 reviews by id ASC**
(QUIRK Q4). It keeps the visible ones and returns `null` if there are none, else `sum(stars) as f32 / count as f32`
(stars = 1..5), emitted as described in §3.2. If the connection returns an error, the result is `null`.
If reading panics (§5.8), the request is aborted.

### 5.7 `_entities`
- `User {id}` → `users` replica; missing → `null` element (no error).
- `Product {id}` → `products` replica; missing → `null`.
- `ProductVariant {id}` → `product_variants` replica; missing → `null`. **This is always the case in the deployment (D1).**
- Representation that is not an object → error `Entity not found.`. A missing `__typename` (or a non-string one) →
  error `"__typename" must be an existing string.`. Unknown typename (for example `Review`), or a missing or unparsable
  `id` → `Entity not found.`. Any error fails the whole `_entities` field: `data: null`.
- Nested `reviews`/`averageRating` resolve as §5.5/§5.6 for the given id, even when the entity came from a `Review.user`
  or `Review.productVariant` embed (no replica lookup there).

### 5.8 Undeserializable review documents (consequence of Q1)
A row whose stored rating is `FourStarst` cannot be read:
- single reads (`review`, `updateReview`, `deleteReview`, the re-read after an insert) → ``<RT> with UUID: `<id>` not found.``
- duplicate check (§5.1 step 6) → treated as existing.
- connection reads: the original **panics** (`bson::from_bson(..).unwrap()` in the pagination library) when such a row is
  among the fetched `L+1` rows. That aborts the HTTP connection without a response (Dapr then answers the gateway
  with HTTP 500). This includes `averageRating` (25+1 rows by id ASC).
  Port: the whole GraphQL request is answered with **HTTP 500** (no GraphQL body), which is what the gateway
  observes through Dapr. Documented deviation: no TCP reset.
- `totalCount` counts such rows normally.

## 6. Events

### 6.1 Published
None.

### 6.2 Subscriptions (`GET /dapr/subscribe`, exact JSON, in this order)
```json
[{"pubsubName":"pubsub","topic":"user/user/created","route":"/on-topic-event"},
 {"pubsubName":"pubsub","topic":"catalog/product/created","route":"/on-topic-event"},
 {"pubsubName":"pubsub","topic":"catalog/product-variant/created","route":"/on-topic-event"}]
```
Common rules for both POST routes (axum `Json<Event<T>>`):
- Content-Type must be JSON (`application/json`, `application/*+json` such as `application/cloudevents+json`, params allowed),
  else **415**. Syntactically invalid JSON → **400**. Valid JSON that does not fit `{topic: string, data: T}`
  (a missing or mistyped field, a bad uuid, a duplicate key) → **422**. Unknown fields are ignored. Body > 2 MiB → 413.
- Success → **200** `{"status":0}` (`application/json`). Dapr cannot parse the numeric status and treats it as delivered.
- Handler failure → **500**, empty body. Dapr redelivers (Redis Streams). There is no idempotency: a duplicate id → 500 forever.

`POST /on-topic-event` (T = `{id: uuid}`):
- `topic == "user/user/created"` → insert `users {id}` (duplicate → 500).
- `topic == "catalog/product/created"` → insert `products {id}` (duplicate → 500).
- any other topic → **500**. **DEFECT D1**: `catalog/product-variant/created` is subscribed on this route, but this
  handler does not accept that topic. Every product-variant event is answered 500 and redelivered forever. The
  product-variant replica stays empty.

`POST /on-product-variant-creation-event` (T = `{id: uuid, productId: uuid}`; not subscribed, only reachable directly):
- `topic == "catalog/product-variant/created"` → insert `product_variants {id, productId}` (duplicate → 500).
- other topic → 500.

Payloads in the deployment: user `{id, username, firstName, lastName, dateJoined}`,
product `{id, internalName, isPubliclyVisible, defaultVariantId, categoryIds}`,
product variant `{id, productId, currentVersionId, isPubliclyVisible}`; all inside the Dapr CloudEvent envelope.

## 7. Persistence (MongoDB → Postgres)

| Mongo collection (DB `review-database`) | Table | Columns |
|---|---|---|
| `users` `{_id}` | `users` | `id uuid PK` |
| `products` `{_id}` | `products` | `id uuid PK` |
| `product_variants` `{_id, product_id}` | `product_variants` | `id uuid PK`, `product_id uuid NOT NULL` |
| `reviews` `{_id, user{_id}, product_variant{_id, product_id}, body, rating, created_at, last_updated_at, is_visible}` | `reviews` | `id uuid PK`, `user_id uuid NOT NULL`, `product_variant_id uuid NOT NULL`, `product_variant_product_id uuid NOT NULL`, `body text NOT NULL`, `rating text NOT NULL` (stored token, see Q1), `created_at timestamptz NOT NULL`, `last_updated_at timestamptz NOT NULL`, `is_visible boolean NOT NULL` |

Indexes: `reviews(user_id)`, `reviews(product_variant_id)`, `reviews(product_variant_product_id)` (non-unique; the original
has no indexes besides `_id`). No foreign keys, because the embedded copies are independent of the replicas. Timestamps are
written truncated to milliseconds (BSON DateTime).

## 8. Quirks and defects (reproduced)

- D1: product-variant events answered 500 (wrong route), replica never filled. So `createReview` always fails with
  the product-variant message, and `_entities(ProductVariant)` is always `[null]`.
- D2: `catalog/product/created` is subscribed, but the docs do not mention it.
- Q1 `FourStarst` on update; Q2 skip off-by-one; Q3 lexicographic rating sort, `CREATED_AT` sorts by
  `last_updated_at`, tiebreak `id DESC`; Q4 `averageRating` only over the first 25 reviews by id; default page size 25.
- No unique (user, variant) constraint (check-then-insert race).
- Event handlers answer `{"status":0}`; duplicates → 500 → endless redelivery.
- Error messages expose Rust type names (`misarch_review::graphql::model::…`).
- Review descriptions: `lastUpdatedAt` is documented as "Timestamp when review was created."; the enum doc says
  `USER_ID` orders by "user_id", `CREATED_AT` by "created_at" (both untrue, see Q3).
- `averageRating` is computed in `f32`.

### Not reproduced (documented deviations)
- Panic → TCP abort is mapped to HTTP 500 (§5.8).
- UUID and Int coercion error texts follow the port's GraphQL engine if they cannot be matched cheaply. Lenient UUID forms
  (simple/braced/urn) are accepted only if the common UUID scalar supports them.
- GraphiQL on `GET /` only if the common stack provides it (no client uses it).
- Mongo failure texts (`… failed in MongoDB.`) are kept for the equivalent Postgres failures.
- Metrics: OTLP via the stack's mechanism (docs/PORTING.md) instead of `axum-otel-metrics` (metric names differ).

## 9. Error strings (verbatim)
- ``misarch_review::graphql::model::review::Review with UUID: `<id>` not found.``
- ``misarch_review::graphql::model::user::User with UUID: `<id>` not found.``
- ``Product variant with the UUID: `<id>` is not present in the system.``
- ``User of UUID: `<u>` has already written a review for product variant of UUID: `<pv>`.``
- `Adding review failed in MongoDB.` / `Retrieving reviews failed in MongoDB.`
- ``Updating body|rating|visibility of review of id: `<id>` failed in MongoDB.`` (`Updating visibility …`)
- ``Deleting review of id: `<id>` failed in MongoDB.``
- `Authentication failed. Authorized-User header is not set or could not be parsed.`
- ``Authentication failed for user of UUID: `<id>`. Operation not permitted.``

## 10. Test plan (proves equivalence)

Unit:
- Authorized-User parsing (valid; unknown role, missing roles, bad uuid and invalid JSON all count as absent; extra keys)
  and the permission matrix with the exact messages.
- Pagination algorithm (§5.5) against a table of cases: total 0; skip 0/null with L rows / L+1 rows; skip>0 with
  ≥L+2, exactly L+1 (off-by-one), ≤L remaining; `first: 0`; default 25.
- Order-key mapping for all 5 fields × 2 directions, including the `id DESC` tiebreak and the lexicographic rating order;
  unsigned-byte UUID comparison (ids with the high bit set).
- Rating token mapping (insert names vs update names incl. `FourStarst`); average in f32 → `4.333333492279053`.
- DateTime formatting (`+00:00`, with and without millis).

Integration (Testcontainers Postgres, full HTTP stack):
- `GET /dapr/subscribe` = the exact JSON of §6.2; `GET /health` 200.
- `/on-topic-event`: user, product → 200 `{"status":0}` and a row; duplicate → 500; product-variant topic → 500 and
  no row (D1); unknown topic → 500; missing `data.id` → 422; bad JSON → 400; `text/plain` → 415; cloudevents+json accepted.
- `/on-product-variant-creation-event`: inserts `{id, productId}`; wrong topic → 500.
- `createReview`: no header / foreign buyer → auth errors (before validation); unknown variant (the deployment case)
  → variant message; unknown user → user message; happy path (variant seeded through the direct route) returns the
  review with `isVisible` default true and `createdAt == lastUpdatedAt`; second create → duplicate message;
  admin may create for another user.
- `updateReview`: not found (no header) → not-found; buyer owner OK; other buyer → auth error; partial updates;
  no-op update keeps `lastUpdatedAt`; `rating: FOUR_STARS` → not-found error, DB holds `FourStarst`, later `review(id)`
  errors, the list containing it → HTTP 500, `averageRating` → HTTP 500, delete → not-found, duplicate create → duplicate message.
- `deleteReview`: true; then `review(id)` null.
- Queries: `reviews` default 25, `first`, `skip`, off-by-one, every order field and direction, `totalCount`;
  `_entities` for User/Product/ProductVariant present and missing (`[null]`), unknown typename → `Entity not found.`;
  `Product.reviews` filters by productId; `User.reviews`; `averageRating` null without visible reviews, f32 value,
  only the first 25 by id counted, invisible reviews ignored.
- SDL: `tools/sdl-diff.sh review` empty.

## 11. Port implementation notes (services/review)

- Stack per docs/PORTING.md + `org.misarch.common.rust` (§11 there): `POST /` with async-graphql's response shape
  (`data` always present), async-graphql `_entities` semantics, lenient `Authorized-User`, uuid-crate `UUID`,
  chrono `DateTime` (`Instant`), axum status codes for the event routes, exact `/dapr/subscribe` JSON.
- Persistence: `DatabaseClient` SQL (`persistence/ReviewStore.kt`); tables of §7; ORDER BY strings of §5.5 in
  `ReviewOrderField.orderBy`; pagination in `MongoCursorPagination` (count query + `LIMIT L+1 [OFFSET skip]`, same
  number of DB round trips as the original: count + find).
- Panics: `RustPanic` → HTTP 500, empty body (§5.8).
- Performance-relevant differences: none intended. The hot path `_entities(ProductVariant)` is one primary-key
  lookup per representation (Mongo: one `find_one` per representation), answered `[null]`.

## Integration fixes (2026-10-13, docs/INTEGRATION.md §4.1)

Found by the whole-system differential run against the running original:
- F5: `GET /health` answers 200 with an empty body like the Rust original (actuator web endpoints off).
