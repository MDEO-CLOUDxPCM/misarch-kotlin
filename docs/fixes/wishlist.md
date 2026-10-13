# Defect fixes: `wishlist`

Baseline: tag `faithful` (docs/specs/wishlist.md). Tests: `tools/build.sh :services:wishlist:test` → 13 green
(11 integration, 1 unit, 1 SDL; the removed unit test covered the random-pick helper of Q1). SDL, app id, topics,
routes and `/dapr/subscribe` unchanged.

## Fixed

### 1. Product-variant validation checked only one random id (spec Q1)
- **Symptom:** `createWishlist` / `updateWishlist` validated only the last element of a Rust `HashSet`'s random
  iteration order; unknown variant ids were stored with a probability of `(n-1)/n` per request, and the same request
  failed or succeeded at random.
- **Root cause:** the original's `fold` ignores its accumulator, so only the last checked id decides.
- **Fix:** all distinct ids are checked with one query (`SELECT id FROM ProductVariantEntity WHERE id = ANY(:ids)`);
  the first unknown id in input order is reported with the original message. Empty lists still pass.
- **Load effect:** `addToWishlist` (`updateWishlist(productVariantIds)`) and `createWishlist` do one replica query for
  all ids (before: one single-row lookup).
- **Tests:** `createWishlist and updateWishlist validate every product variant (FIXED Q1)`.

### 2. Over-long page with `skip > 0` (spec Q3)
- **Symptom:** `User.wishlists(first, skip > 0)` returned `first + 1` wishlists and `hasNextPage: false` when exactly
  `first + 1` rows remained (mongodb-cursor-pagination 0.3.2); `first: 0, skip: 7` of 8 returned one node.
- **Fix:** `hasNextPage` = the extra fetched row exists, for every `skip`; that row is then dropped.
- **Tests:** `User wishlists - pagination of mongodb-cursor-pagination (FIXED skip off-by-one Q3)` (table of cases).

### 3. Replication events not idempotent (spec Q8)
- **Symptom:** a redelivered `user/user/created` or `catalog/product-variant/created` answered 500 and was redelivered
  forever.
- **Fix:** `INSERT … ON CONFLICT (id) DO NOTHING`; a duplicate answers 200 `{"status":0}`. Unknown topics still 500.
- **Tests:** `topic events - success, idempotent duplicates (FIXED), unknown topics and malformed bodies`.

## Payload / contract changes
None.

## Not fixed (deliberately)
- **Q2 default page size 25** for `User.wishlists` without `first`: API behaviour of the original's pagination library.
- **Q4 `_id DESC` tie-breaker / `USER_ID` order**: all wishlists of `User.wishlists` share the user id, so `USER_ID`
  can only order by the tie-breaker; library convention, not wrong.
- **Q5 `_entities(User)` without authorization**: it only returns `{id}`; `User.wishlists` authorizes.
- **Q6 validation before the user check / existence before authorization**: error precedence only.
- **Q7 Rust type paths in error texts**: wording.
- **Q9 users/variants never deleted**: no deletion events exist in the system.
- **Non-transactional `updateWishlist`** (variants then name as two updates): only DB failures can split it.
