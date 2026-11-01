# Defect fixes: `shoppingcart`

The faithful port (commit `060498d`) reproduced the defects of the original Rust service; they are fixed as
described below. The service has since been rewritten in the style of the Kotlin services (docs/specs/shoppingcart.md,
§8 lists every visible difference to the original); the fixes are unchanged. The federation SDL is unchanged
(`tools/sdl-diff.sh shoppingcart` empty). No events are published.

## Fixed

### C1 — `createShoppingcartItem` never reused the item of a variant (quirk Q1)

- **Symptom:** "Queries for existing item, otherwise adds new shoppingcart item" (SDL description): the lookup always
  failed (its positional projection drops `_id` and `last_updated_at`, so deserializing the `User` fails and the
  error is treated as "not found"), so every call appended a new item. Carts of load-test users grew with every
  aborted buy session (the calibration needs `maintain.py` to clean them), and every cart read — in particular
  order's `createOrder` invocation, which reads the whole cart — got more expensive over time.
- **Fix:** per the original's code (`Ok(item) => Ok(item)`): if the user already has an item for the product variant,
  that item (the first added) is returned **unchanged** (no count change, nothing inserted); otherwise the new item is
  inserted. One transaction, the user row locked (`SELECT … FOR UPDATE`), so concurrent calls for one user and variant
  create one item.
- **Load effect:** carts stay bounded by the number of distinct variants; one extra indexed lookup per call.
- **Tests:** `createShoppingcartItem reuses the item of the same variant and sets lastUpdatedAt` (sequential and 4
  concurrent calls).
- **Contract:** none (the returned item for a known variant is the existing one, with its count).

### C2 — multi-item validation checked one random variant (quirk Q2)

- **Symptom:** `updateShoppingcart` folded over the variant ids but kept only the result of the last element of the
  random `HashSet` order: with k unknown variants among n, the mutation failed with probability ≈ k/n and otherwise
  stored unknown product variant ids in the cart.
- **Fix:** every variant is checked (one `id = ANY(?)` lookup); the error names the first unknown variant in input
  order (`Product variant with id <id> does not exist`).
- **Tests:** `updateShoppingcart - authorization, null items, unknown user and product variants`.

### C3 — duplicate replication events failed forever (quirk Q8)

- **Symptom:** a redelivered `user/user/created` or `catalog/product-variant/created` (e.g. the duplicate user
  publish of the seeding) hit the primary key: HTTP 500, Dapr redelivered it forever (the calibration needs a reaper
  for these deliveries); a permanent load of failing deliveries.
- **Fix:** `INSERT … ON CONFLICT (id) DO NOTHING`: 200, no change (the existing cart and its
  `lastUpdatedAt` stay).
- **Tests:** `replica events are idempotent`.
- **Contract:** status code of duplicate deliveries (500 → 200).

### C4 — `lastUpdatedAt` not updated by cart changes (quirk Q4)

- **Symptom:** "Timestamp when shopping cart was last updated." changed only on `updateShoppingcart` (and user
  creation); adding, updating, deleting items and the removal of ordered items left it unchanged.
- **Fix:** set to now (ms) in the same transaction as the change: item added (= its `addedAt`; not when an existing
  item is returned), count updated, item deleted, ordered items removed (only if any were removed; a redelivered order
  event changes nothing).
- **Tests:** in the C1 test, `order created removes the ordered items of that user only, idempotently`,
  `shoppingcartItem, updateShoppingcartItem and deleteShoppingcartItem - existence, authorization, lastUpdatedAt`.
- **Load effect:** one more `UPDATE` of the user row per item change.

## Not fixed (deliberately)

| Item | Reason |
|---|---|
| Q3 `updateShoppingcart` validates the variants before the user; `shoppingCartItems: null` skips both checks | order of checks / error precedence only; no wrong work |
| Q5 `count: 0` accepted and kept | no SDL or doc statement forbids it; a count-0 item is a valid (if odd) state |
| Q6 `_entities` unauthenticated, `shoppingcartItem` existence before authorization | access-control hardening, not a flow defect; entity resolution is used by the gateway (federation joins) and by order's `createOrder` invocation, and adding checks there risks breaking those callers |

The original's Rust type paths and "MongoDB" in error texts (Q7, Q10), the 500 for an unknown topic on a shared route
(Q9) and the all-or-nothing `_entities` (Q11) are gone with the rewrite (docs/specs/shoppingcart.md §8).
