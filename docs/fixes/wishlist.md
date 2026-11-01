# Defect fixes: `wishlist`

Baseline: tag `faithful` (docs/specs/wishlist.md). The service has since been rewritten in the style of the Kotlin
services (docs/specs/wishlist.md §9/§10); the fixes are unchanged, test names refer to `WishlistIntegrationTest`.
SDL, app id and topics unchanged.

## Fixed

### 1. Product-variant validation checked only one random id (spec Q1)
- **Symptom:** `createWishlist` / `updateWishlist` validated only the last element of a Rust `HashSet`'s random
  iteration order; unknown variant ids were stored with a probability of `(n-1)/n` per request, and the same request
  failed or succeeded at random.
- **Root cause:** the original's `fold` ignores its accumulator, so only the last checked id decides.
- **Fix:** all distinct ids are checked with one query (`… FROM ProductVariantEntity t WHERE t.id = ANY(?)`); the
  first unknown id in input order is reported. Empty lists still pass.
- **Load effect:** `addToWishlist` (`updateWishlist(productVariantIds)`) and `createWishlist` do one replica query for
  all ids (before: one single-row lookup).
- **Tests:** `createWishlist - authorization, validation and result`, `updateWishlist and deleteWishlist`.

### 2. Over-long page with `skip > 0` (spec Q3)
- **Symptom:** `User.wishlists(first, skip > 0)` returned `first + 1` wishlists and `hasNextPage: false` when exactly
  `first + 1` rows remained (mongodb-cursor-pagination 0.3.2); `first: 0, skip: 7` of 8 returned one node.
- **Fix:** `hasNextPage` = the extra fetched row exists, for every `skip`; that row is then dropped.
- **Tests:** `User wishlists - order fields, ties by id descending, pagination, default page size 25`.

### 3. Replication events not idempotent (spec Q8)
- **Symptom:** a redelivered `user/user/created` or `catalog/product-variant/created` answered 500 and was redelivered
  forever.
- **Fix:** `INSERT … ON CONFLICT (id) DO NOTHING`; a duplicate answers 200.
- **Tests:** `replica events are idempotent`.

## Payload / contract changes
None.

## Not fixed (deliberately)
- **Q2 default page size 25** for `User.wishlists` without `first`: API behaviour of the original's pagination library.
- **Q4 `_id DESC` tie-breaker / `USER_ID` order**: all wishlists of `User.wishlists` share the user id, so `USER_ID`
  can only order by the tie-breaker; library convention, not wrong.
- **Q5 `_entities(User)` without authorization**: it only returns `{id}`; `User.wishlists` authorizes.
- **Q6 validation before the user check / existence before authorization**: error precedence only.
- **Q9 users/variants never deleted**: no deletion events exist in the system.

The Rust type paths in error texts (Q7) and the non-transactional `updateWishlist` are gone with the rewrite
(docs/specs/wishlist.md §10).
