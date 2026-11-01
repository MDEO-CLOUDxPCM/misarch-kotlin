# Service spec: `review`

Original: `/home/devbox/workspace/misarch/review` (Rust 2024 edition, axum 0.8.3, async-graphql 7.0.16,
mongodb driver 2.8, `mongodb-cursor-pagination` 0.3.2, bson 2.14). Crate `misarch-review`.
Reference SDL: `reference/sdl/review.graphql` (captured from the running original's `_service { sdl }`). It matches the
code of the original. The schemas repo copy (`misarch/schemas/review.graphql`, which the gateway composes) is **older**
and differs in 3 places (§3.1). The original also subscribes `catalog/product/created` (its docs do not mention it).

This service is written like the Kotlin services (entities, `CoroutineCrudRepository`s, services, GraphQL
`Query`/`Mutation`, one `EventController`). §1-§9 describe the original; §10 is the implementation, §11 lists every
visible difference to the original. Fixed defects: `docs/fixes/review.md`.

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
  **FIXED** (docs/fixes/review.md §1): the replica is filled, reviews can be written.

## 2. Runtime contract of the original (kept except for §11)

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
unknown id, `averageRating` without visible reviews). The service follows the code and `reference/sdl`, so it sends the
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
- **QUIRK Q1 (FourStarst)** — **FIXED** (docs/fixes/review.md §3: updates store `FourStars`, migration V2 repairs old rows): the update path stores ratings through a hand-written `to_string()`:
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
  and `hasNextPage = false`. **FIXED** (docs/fixes/review.md §5): `hasMore = len(rows) > L` for every `skip`.
- `first: 0` → limit 1 → at most 0 nodes; `hasNextPage` true if any row matched.
- **Now (relational, 2026-10-31, docs/PORTING.md §6)**: the connection is the Kotlin services' connection pattern
  (`ConnectionQuery` of libs/common), each field one statement run only when selected:
  `nodes` = `SELECT t.* FROM reviews t WHERE <filter> ORDER BY <sort> LIMIT L OFFSET skip`,
  `totalCount` = `SELECT COUNT(t.id) ...`, `hasNextPage` = `SELECT 1 ... LIMIT 1 OFFSET L + skip` (a further row exists).
  Same results as the FIXED algorithm above for every `first`/`skip` (incl. `total = 0`, `first: 0`); `first`/`skip` are
  coerced (negative → `Failed to parse "Int": Invalid number`) in the field resolver as before.
- **QUIRK Q3 (sort keys)**, the field → Mongo key mapping in `ReviewOrderField::as_str`. **FIXED** for `RATING`
  (now by number of stars) and `CREATED_AT` (now `created_at`), docs/fixes/review.md §6:

| GraphQL field | Mongo key | Current ORDER BY (dir = requested direction) |
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
**FIXED** (docs/fixes/review.md §4): one aggregate over **all** visible reviews (still `f32`).
If reading panics (§5.8), the request is aborted.

### 5.7 `_entities`
- `User {id}` → `users` replica; missing → `null` element (no error).
- `Product {id}` → `products` replica; missing → `null`.
- `ProductVariant {id}` → `product_variants` replica; missing → `null`. **This is always the case in the deployment (D1).** (FIXED: the replica is filled.)
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
  Now: the whole GraphQL request is answered with **HTTP 500** (no GraphQL body), which is what the gateway
  observes through Dapr. Documented deviation: no TCP reset.
- `totalCount` counts such rows normally. Now: the panic is raised by `nodes` only, i.e. only when `nodes` is selected and such a row is on the page
  (before 2026-10-31 also for a `totalCount`/`hasNextPage`-only selection whose page contained one, because the page
  was always fetched). Unreachable since migration V2 repaired the rows and `FourStarst` is no longer written.

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
  **FIXED** (docs/fixes/review.md §2): replica inserts are `ON CONFLICT DO NOTHING`, a duplicate answers 200.

`POST /on-topic-event` (T = `{id: uuid}`):
- `topic == "user/user/created"` → insert `users {id}` (duplicate → 500).
- `topic == "catalog/product/created"` → insert `products {id}` (duplicate → 500).
- `topic == "catalog/product-variant/created"` → **FIXED** (docs/fixes/review.md §1): reads `{id, productId}`
  and inserts `product_variants` (missing `productId` → 422).
- any other topic → **500**. **DEFECT D1** (original behaviour): `catalog/product-variant/created` is subscribed on this route, but this
  handler does not accept that topic. Every product-variant event is answered 500 and redelivered forever. The
  product-variant replica stays empty.

`POST /on-product-variant-creation-event` (T = `{id: uuid, productId: uuid}`; not subscribed, only reachable directly):
- `topic == "catalog/product-variant/created"` → insert `product_variants {id, productId}` (duplicate → 500).
- other topic → 500.

Payloads in the deployment: user `{id, username, firstName, lastName, dateJoined}`,
product `{id, internalName, isPubliclyVisible, defaultVariantId, categoryIds}`,
product variant `{id, productId, currentVersionId, isPubliclyVisible}`; all inside the Dapr CloudEvent envelope.

## 7. Persistence of the earlier version (MongoDB → Postgres; current tables: §10)

| Mongo collection (DB `review-database`) | Table | Columns |
|---|---|---|
| `users` `{_id}` | `users` | `id uuid PK` |
| `products` `{_id}` | `products` | `id uuid PK` |
| `product_variants` `{_id, product_id}` | `product_variants` | `id uuid PK`, `product_id uuid NOT NULL` |
| `reviews` `{_id, user{_id}, product_variant{_id, product_id}, body, rating, created_at, last_updated_at, is_visible}` | `reviews` | `id uuid PK`, `user_id uuid NOT NULL`, `product_variant_id uuid NOT NULL`, `product_variant_product_id uuid NOT NULL`, `body text NOT NULL`, `rating text NOT NULL` (stored token, see Q1), `created_at timestamptz NOT NULL`, `last_updated_at timestamptz NOT NULL`, `is_visible boolean NOT NULL` |

Indexes: `reviews(user_id)`, `reviews(product_variant_id)`, `reviews(product_variant_product_id)` (non-unique; the original
has no indexes besides `_id`). No foreign keys, because the embedded copies are independent of the replicas. Timestamps are
written truncated to milliseconds (BSON DateTime).

## 8. Quirks and defects of the original

- D1 (**FIXED**): product-variant events answered 500 (wrong route), replica never filled. So `createReview` always fails with
  the product-variant message, and `_entities(ProductVariant)` is always `[null]`.
- D2: `catalog/product/created` is subscribed, but the docs do not mention it.
- Q1 `FourStarst` on update (**FIXED**); Q2 skip off-by-one (**FIXED**); Q3 lexicographic rating sort, `CREATED_AT` sorts by
  `last_updated_at` (both **FIXED**), tiebreak `id DESC` (kept); Q4 `averageRating` only over the first 25 reviews by id
  (**FIXED**); default page size 25 (kept).
- No unique (user, variant) constraint (check-then-insert race). **FIXED**: unique constraint.
- Event handlers answer `{"status":0}`; duplicates → 500 → endless redelivery (**FIXED**: idempotent, 200).
- Error messages expose Rust type names (`misarch_review::graphql::model::…`).
- Review descriptions: `lastUpdatedAt` is documented as "Timestamp when review was created."; the enum doc says
  `USER_ID` orders by "user_id", `CREATED_AT` by "created_at" (both untrue, see Q3).
- `averageRating` is computed in `f32`.

## 9. Error strings of the original
- ``misarch_review::graphql::model::review::Review with UUID: `<id>` not found.``
- ``misarch_review::graphql::model::user::User with UUID: `<id>` not found.``
- ``Product variant with the UUID: `<id>` is not present in the system.``
- ``User of UUID: `<u>` has already written a review for product variant of UUID: `<pv>`.``
- `Adding review failed in MongoDB.` / `Retrieving reviews failed in MongoDB.`
- ``Updating body|rating|visibility of review of id: `<id>` failed in MongoDB.`` (`Updating visibility …`)
- ``Deleting review of id: `<id>` failed in MongoDB.``
- `Authentication failed. Authorized-User header is not set or could not be parsed.`
- ``Authentication failed for user of UUID: `<id>`. Operation not permitted.``

## 10. Implementation (services/review)

Tables (Flyway `V1_1__create_relations.sql`):

```sql
UserEntity (id UUID PRIMARY KEY)                                  -- replica, user/user/created
ProductEntity (id UUID PRIMARY KEY)                               -- replica, catalog/product/created
ProductVariantEntity (id UUID PRIMARY KEY, productId UUID NOT NULL) -- replica, catalog/product-variant/created
ReviewEntity (id UUID PRIMARY KEY DEFAULT uuid_generate_v4(), userId, productVariantId, productId UUID NOT NULL,
    body TEXT, rating TEXT CHECK (rating IN ('ONE_STARS', …, 'FIVE_STARS')), createdAt, lastUpdatedAt TIMESTAMPTZ,
    isVisible BOOLEAN, stars SMALLINT GENERATED ALWAYS AS (1..5 from rating) STORED)
-- indexes: productVariantId, productId, UNIQUE (userId, productVariantId) (also serves userId)
```

- Entities `ReviewEntity` (`rating: Rating`, stored as the enum constant name), `UserEntity`, `ProductEntity`,
  `ProductVariantEntity`; repositories with the idempotent replica inserts (`ON CONFLICT (id) DO NOTHING`),
  `existsByUserIdAndProductVariantId` and the averages (`AVG(stars::float8) … AND isVisible`).
- `ReviewService` (`@Transactional`): `createReview` (product variant must be replicated, user must be replicated,
  one review per user and variant, also enforced by the unique constraint), `updateReview`/`deleteReview` (review must
  exist, then author or employee/admin; an update without fields changes nothing, otherwise `lastUpdatedAt = now`).
  `Mutation` checks that `createReview`'s `userId` is the caller or the caller is an employee/admin.
- `Query.reviews`, `Product/ProductVariant/User.reviews`: `ReviewConnection` (`BaseConnection` over `ReviewEntity`
  with `productId`/`productVariantId`/`userId` conditions), default page size 25, `PagingValidation.NON_NEGATIVE`,
  order columns `id`, `userId`, (`productVariantId`, `productId`), `stars`, `createdAt`, ties ordered by `id DESC`;
  each field one statement, run only when selected. `Query.review(id)`: the review or `null`.
- `averageRating`: one aggregate statement, `null` without visible reviews.
- Federation: `UserResolver`, `ProductResolver`, `ProductVariantResolver` (`DataLoaderEntityResolver` over the
  replicas).
- `EventController`: `/subscription/<topic>` for the three topics.

## 11. Deviations from the original

Project-wide: Postgres instead of MongoDB (env `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`,
`SPRING_R2DBC_PASSWORD` replace `MONGODB_URI`), OpenTelemetry Java agent, Dapr SDK endpoints (`/dapr/config`,
`/healthz`). Visible through the API:

- **Event routes**: `/subscription/<topic>` per topic instead of the shared `/on-topic-event` (and the unsubscribed
  `/on-product-variant-creation-event` is gone); `/dapr/subscribe` is generated by the Dapr SDK (same topics and
  pub/sub component). Success answers are 200 with an empty body (was `{"status":0}`); malformed bodies 400 (was
  415/400/422).
- **HTTP surface**: `/health` answers `{"status":"UP"}` (was an empty 200); GraphiQL at `/graphiql` (was `GET /`).
- **Error shape and texts** (graphql-java): `Exception while fetching data (/path) : <message>`; `Unauthorized
  access` / `Unauthorized access: <id> is not an employee or admin` (were `Authentication failed …`); `Product variant
  with id <id> does not exist`, `User with id <id> does not exist`, `User with id <u> has already reviewed product
  variant with id <pv>`, `null` (`NoSuchElementException`) for an unknown review in `updateReview`/`deleteReview`,
  `Limit/Offset must not be negative.` (were the Rust type paths, `… failed in MongoDB.` and async-graphql's parse
  texts); an unparsable `Authorized-User` header fails the request (was: treated as absent).
- **`_entities`**: an id that is not replicated resolves to `null` with an error (was `null` without an error); an
  unparsable id or unknown type gives graphql-kotlin's error for that element (was `Entity not found.` for the whole
  field).
- **`averageRating`** is a double average (`13/3` → `4.333333333333333`; was `f32`, `4.333333492279053`).
- **Scalars**: `UUID` input via `UUID.fromString` (the uuid crate also accepted simple, braced and URN forms);
  `DateTime` written as `yyyy-MM-ddTHH:mm:ss.SSSZ` (was chrono's `…+00:00`).
- **`updateReview`** writes all given fields in one statement (was one update per field); an `updateReview` without
  fields still changes nothing.
- The rows with an unreadable rating (the original's `FourStarst`) cannot exist any more (V2 repaired them, V3 adds a
  check constraint), so the panic path is gone.
- The defects of `docs/fixes/review.md` are fixed.

## 12. Tests

`tools/build.sh :services:review:test`: `ReviewIntegrationTest` (subscriptions, health, idempotent replica events,
product-variant replica and `createReview`, authorization and validation, one review per user and variant incl.
concurrent creation, update/delete, partial update and no-op, review by id, paging incl. default 25 and argument
errors, lazy connection statements, order fields and ties, nested connections, average ratings, entities),
`ReviewSchemaTest` (generated ids and `stars`, unknown rating rejected, one review per user and variant), `ReviewSdlTest`
(`tools/sdl-diff.sh review` empty).
