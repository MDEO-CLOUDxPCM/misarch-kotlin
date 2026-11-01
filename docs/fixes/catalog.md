# Defect fixes: `catalog`

Baseline: tag `faithful` (docs/specs/catalog.md). Tests: `tools/build.sh :services:catalog:test` → 25 green
(19 integration, 1 SDL, 5 unit). SDL, app id, topics, routes, `/dapr/subscribe` and all six event payloads unchanged
(incl. the `publiclyVisible` key, which order now accepts, see below).

## Fixed

### 1. One unknown id failed the whole data-loader batch (spec §4.1, quirk 5)
- **Symptom:** every loader used `entities[it]!!`: one unknown id failed all keys of the batch with a
  NullPointerException. The gateway batches entity references (`_entities(Product|ProductVariant|
  ProductVariantVersion)` for cart, order, wishlist and review views), so one stale reference blanked the whole list.
  The Categorical/Numerical loaders additionally failed the batch with a ClassCastException for an id of the other
  subtype.
- **Fix:** libs/common `BaseIdDataLoader` fails missing keys individually (for every loader): only the unknown key fails with `NoSuchElementException()` (text unchanged:
  `Exception while fetching data (/product) : null`). The Categorical/Numerical loaders drop entities of the other
  subtype in `loadAll`, so such an id behaves like an unknown one.
- **Tests:** `entities of every resolvable type` (unknown product next to a known one; categorical id requested as
  numerical next to a numerical one; unknown `product(id)` text).

### 2. Replication events not idempotent (quirk 13)
- **Symptom:** a redelivered `tax/tax-rate/created` or `media/media/created` hit the primary key, answered 500 and was
  redelivered forever.
- **Fix:** `INSERT … ON CONFLICT (id) DO NOTHING`; a duplicate answers 200.
- **Tests:** `tax rate and media events are registered, duplicates are acknowledged (FIXED), extra fields are ignored`.

### 3. Duplicate `categoryIds` in `createProduct` failed with a database error (quirk 9)
- **Symptom:** `categoryIds: [a, a]` failed with a unique-constraint violation of `ProductToCategoryEntity` (a raw DB
  message), although the published event deduplicates (`toSet()`) and duplicate `mediaIds` are deduplicated silently.
- **Fix:** the ids are deduplicated (order kept) before the existence check and the inserts.
- **Tests:** `createProduct ignores duplicate category ids (FIXED)`.

### 4. Duplicate version numbers under concurrency (quirk 8)
- **Symptom:** `version = MAX(version) + 1` without locking; concurrent `createProductVariantVersion` calls for the same
  variant got the same version number (verified: 6 concurrent calls without the lock all got version 2).
- **Fix:** `SELECT id FROM ProductVariantEntity WHERE id = :id FOR UPDATE` before `MAX` (inside the mutation's
  transaction, so the lock is held until commit). One extra statement per version creation; no contention otherwise.
- **Tests:** `concurrent createProductVariantVersion calls get distinct version numbers (FIXED)` (fails without the lock).

## Payload / contract changes
None. `catalog/product-variant/updated` and the other events keep `publiclyVisible` (Jackson bean naming of the
original). Consumers: the order port (other agent) now reads `publiclyVisible` (and accepts `isPubliclyVisible`);
review reads `id`/`productId` of `catalog/product-variant/created` (fixed on the review side).

## Not fixed (deliberately)
- **Quirk 1 `publiclyVisible` instead of `isPubliclyVisible`**: changing the key would be a payload change for every
  consumer; order was adapted to the existing key instead (minimal payload change policy).
- **Quirk 2 events published inside the transaction, before commit**: a publish failure rolls the DB back but earlier
  events of the same mutation stay published (e.g. `product/created` without the product). A correct fix needs a
  transactional outbox (new table + relay), which changes the load profile of every mutation; only reachable when the
  Dapr sidecar fails mid-mutation.
- **Quirk 3 no `product-variant/updated` when a new version becomes current**: the only consumer of that topic
  (order) learns the current version from `product-variant-version/created`.
- **Quirk 4 update events even without changes**: harmless duplicates for consumers.
- **Quirk 6 `_entities` without visibility checks; `internalName` employee-only**: federation must resolve references
  to hidden products (old carts/orders); `internalName` is internal by design.
- **Quirk 7 one query per connection field; `hasNextPage` without ORDER BY**: correct results.
- **Quirks 10-12, 14, 15** (category inserted before name validation and rolled back; message without period;
  `TaxRate` DB lookup to build a stub, `Media`/`TaxRate` not resolvable; SDL description typos; event `createdAt`
  precision): no wrong result, wording, or SDL that must stay identical.
