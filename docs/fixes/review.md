# Defect fixes: `review`

Baseline: tag `faithful` (behaviour of the Rust original incl. its defects, docs/specs/review.md). The service has
since been rewritten in the style of the Kotlin services (tables `ReviewEntity` etc., migration V3; docs/specs/review.md
§10/§11); the fixes below are unchanged, test names refer to `ReviewIntegrationTest`. SDL, app id and topics are
unchanged.

## Fixed

### 1. Product-variant replica never filled (spec D1)
- **Symptom:** `catalog/product-variant/created` is subscribed on `/on-topic-event`, which answered 500 for that topic,
  so Dapr redelivered every variant event forever and the `product_variants` table stayed empty. Consequences:
  `createReview` always failed (`Product variant … is not present in the system.`), there were no reviews (the
  calibration seeder worked around it by writing rows into the table directly), and the gateway's hot-path call
  `_entities(ProductVariant){averageRating}` (product page of every buy iteration) always answered `[null]`, i.e. it
  did a single primary-key lookup and never computed a rating. Redis kept a growing pending list for review.
- **Root cause:** the original's `on_topic_event` handler only knows the user and product topics; the variant handler
  (`on_product_variant_creation_event`) exists but is registered under another route that is not subscribed.
- **Fix:** `/on-topic-event` also handles `catalog/product-variant/created` and reads `data.productId` (missing or
  malformed → 422, like every other field). `/dapr/subscribe` stays byte-identical; the direct route is kept.
- **Load effect:** `createReview` now writes reviews; `_entities(ProductVariant)` resolves the variant and
  `averageRating` runs its aggregate query (see 4), i.e. the product page now does the intended review work.
- **Tests:** `product variant events are stored with their product and enable createReview`.

### 2. Replication events not idempotent
- **Symptom:** a redelivered `user/user/created`, `catalog/product/created` (or variant) event hit the primary key
  and answered 500, so Dapr redelivered it forever (at-least-once delivery makes duplicates normal).
- **Fix:** `INSERT … ON CONFLICT (id) DO NOTHING` for the three replicas; a duplicate answers 200.
  Unknown topics still answer 500.
- **Tests:** `replica events are stored and idempotent`.

### 3. `updateReview(rating: FOUR_STARS)` broke the review (spec Q1, "FourStarst")
- **Symptom:** the update path wrote the token `FourStarst` (typo in the hand-written `to_string()`); the re-read
  failed, the mutation answered not-found, the review became unreadable, and every list or `averageRating` that
  fetched it aborted the whole request (HTTP 500 via Dapr).
- **Fix:** updates store the same serde names as inserts (`FourStars`). Migration `V2__fix_ratings_unique_review.sql`
  repairs rows written by an older version (`UPDATE … SET rating = 'FourStars' WHERE rating = 'FourStarst'`).
  The panic path for unreadable rows stays (now unreachable for data written by this version).
- **Tests:** `updateReview - partial updates and no-op` (FOUR_STARS stored and readable). Since V3 ratings are stored as
  the enum names (`FOUR_STARS`) with a check constraint.

### 4. `averageRating` only over the first 25 reviews (spec Q4)
- **Symptom:** the average used the default connection page (first 25 reviews by id), so products/variants with more
  reviews showed a wrong average.
- **Fix:** one aggregate query over all visible reviews (since V3 `AVG(stars)` with the filter on `productVariantId` /
  `productId`, both indexed), null without visible reviews.
- **Load effect:** one aggregate query instead of count + fetch of up to 26 rows per `averageRating`.
- **Tests:** `averageRating over all visible reviews` (now a double average, `AVG(stars)`).

### 5. Pagination off-by-one with `skip > 0` (spec Q2)
- **Symptom:** with `skip > 0` and exactly `first + 1` rows remaining, `first + 1` nodes were returned with
  `hasNextPage: false` (mongodb-cursor-pagination 0.3.2).
- **Fix:** the connection fetches `first + 1` rows for every `skip`; `hasNextPage` = the extra row exists, which is
  then dropped. Default page size 25 is kept (see "Not fixed"). Since the unification the connection is the common
  `BaseConnection` (`LIMIT first OFFSET skip`, `hasNextPage` probes the row after the page).
- **Tests:** `reviews connection - default page size 25, first, skip, hasNextPage`.

### 6. Wrong sort keys (spec Q3)
- **Symptom:** `orderBy: RATING` sorted the stored strings lexicographically (`FiveStars < FourStars < OneStars < …`);
  `orderBy: CREATED_AT` sorted by `last_updated_at`.
- **Fix:** `RATING` orders by the number of stars (since V3 the generated column `stars`), `CREATED_AT` by
  `created_at`. The `id DESC` tiebreak of the original library is kept.
- **Tests:** `order fields - rating by stars, ties by id descending`.

### 7. Duplicate reviews possible under concurrency
- **Symptom:** the one-review-per-(user, variant) rule was a check-then-insert without a constraint; two concurrent
  `createReview` calls could both succeed.
- **Fix:** unique index `reviews_user_id_product_variant_id_key` (migration V2; it first deletes younger duplicates
  that an older version may have written). An insert that hits it answers the usual duplicate message.
- **Tests:** `concurrent createReview of one user and product variant stores one review`.

## Payload / contract changes
None. The incoming variant payload (`{id, productId, currentVersionId, publiclyVisible}`) is the one catalog already
publishes; only `id` and `productId` are read.

## Not fixed (deliberately)
- **Default page size 25** when `first` is absent: the original's API behaviour; the SDL does not promise "all".
- **`id DESC` tiebreak** for non-`ID` orders: a stable order, not wrong.
- **Odd SDL descriptions** (`lastUpdatedAt` "Timestamp when review was created.", `USER_ID` "Orders by user_id"):
  the SDL must stay identical.
- **The gateway's schema copy declares `averageRating: Float!` / `review: Review!`**: SDL issue, cannot be fixed here.

The Rust type names in error messages, the non-atomic `updateReview` and the panic path for unreadable rows are gone
with the rewrite (docs/specs/review.md §11).
