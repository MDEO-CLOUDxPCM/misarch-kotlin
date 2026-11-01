# inventory: fixed defects

Baseline: tag `faithful` (docs/specs/inventory.md §8). SDL and event payloads unchanged. Since the unification
(Kotlin entities/repositories/services, docs/specs/inventory.md §12) the fixes below are covered by
`InventoryIntegrationTest`; the class-validator corpus test is gone with the emulation.

## 1. Concurrent reservations of one variant failed (spurious `reservation-failed`)

- **Symptom:** two orders reserving the same variant at the same time conflicted; the later one failed with
  `WriteConflict …` (Mongo snapshot transaction; port: REPEATABLE READ + `40001`) although enough items were in
  stock, so the order got `reservation-failed` and its other items were released. Under load with popular variants
  this rejects a share of the buy sagas.
- **Fix:** one statement in a READ COMMITTED transaction:
  `UPDATE … SET RESERVED, orderId WHERE id IN (SELECT id … IN_STORAGE ORDER BY seq LIMIT n FOR UPDATE SKIP LOCKED)
  RETURNING *`; fewer than `n` rows → `Not enough product items …` and rollback. Concurrent reservations take
  different items. (PORTING.md §6 already recommends `SKIP LOCKED` for "pick any free item".)
- **Tests:** 6 concurrent reservations of 3 of 20 items all succeed with 18 distinct items; then 3 × 1 of 2 left →
  2 succeed, 1 "Not enough".

## 2. `reserveProductItemBatch` returned `[]`; `number` 0 reserved everything

- **Root cause:** the reload query was `find({filter: {_id: {$in: ids}}})` (matches nothing); Mongo `limit(0)` means
  "no limit", `limit(-n)` means n.
- **Fix:** returns the reserved items; `number <= 0` reserves nothing (as `createProductItemBatch` does for `<= 0`).
- **Tests:** reserved items returned; 0 and -1 reserve nothing.

## 3. Phantom items on release

- **Symptom:** every release of an order without assigned items (failed reservation of all items,
  `discount/order/validation-failed` or `payment/payment/payment-failed` for such an order, redeliveries) inserted an
  `IN_STORAGE` item without product variant (`updateMany(..., {upsert: true})`); the table grew and listing queries
  selecting `productVariant` failed with a non-null error.
- **Fix:** plain update, nothing inserted. A release/status update with a missing order id does nothing (the original's
  `{orderId: null}` matched every unassigned item); the event DTOs now require the order id. Migration
  `V3__uuid_ids.sql` deletes phantom rows left in existing databases (`productVariantId` is `NOT NULL`).
- **Tests:** no phantom after failed reservation, discount validation failure and payment failure of empty orders.

## 4. Non-idempotent `order-created`; result publish fire-and-forget; errors swallowed

- **Symptom:** a redelivered `order/order/created` reserved a second set of items for the same order; the result event
  was published fire-and-forget (a failed publish was only logged → saga stopped silently); an internal error was
  *returned* as a 201 body, so Dapr dropped the event and the order was stuck.
- **Fix:** per order item only the items not yet assigned to the order are reserved (top-up to `count`); the result
  event is published before the handler answers and a failed publish answers 500 (Dapr redelivers, the reservation is
  idempotent and the result is re-published); other errors answer 500.
- **Tests:** redelivery reserves nothing more and re-publishes; publish failure → 500, redelivery → 201 with one
  reservation.

## 5. Fire-and-forget partial creation and releases terminated the process

- **Symptom:** a duplicate `catalog/product-variant/created` (redelivery) answered 201 and then terminated the process
  (duplicate key in a detached promise; port: `halt(1)`), restarting inventory; a failing detached release
  (`payment-failed`, `discount-validation-failed`) did the same.
- **Fix:** awaited and idempotent (`ON CONFLICT DO NOTHING` for the replicated variant); failures answer 500 (Dapr
  redelivers). Nothing runs detached any more, so `MISARCH_EXIT_ON_UNHANDLED_ERROR` is gone.
- **Tests:** duplicate variant event → 201, no termination; releases complete before the answer.

## 6. Filter mapping: nodes always empty with a filter

- **Symptom:** `productItems(filter: …)` returned no nodes whenever a filter field was set (fields mapped to
  non-existent Mongo fields); `ProductVariant.productItems` ignored its `filter` argument, always returned no nodes and
  counted all statuses.
- **Fix:** nodes and totalCount use the same filter (`productVariant`, `inventoryStatus`; explicit null = no condition);
  `ProductVariant.productItems` = items of the variant filtered by `inventoryStatus`.
- **Tests:** variant, status and combined filters; the entity field with/without status filter.

## 7. Connection fields selected through fragments not computed

- **Fix:** each connection field runs its own statement when it is resolved (`ConnectionQuery`), however it is selected.
- **Tests:** inline fragment + named fragment.

## 8. `deleteProductItem` of a missing item

- **Fix:** `ProductItem with ID "<id>" not found` (like `productItem`/`updateProductItem`) instead of the non-null
  violation with `data: null`.

## Not fixed (deliberately)

- No state machine for item statuses (a late `payment-failed` releases shipped/delivered items; `updateProductItem`
  keeps `orderId`): the original has no transition rules to follow; with the payment fixes (prepayment success stored)
  the main trigger of late releases is gone.
- `inventory/product-item/reservation-failed` has no consumer (order does not react): see docs/fixes/order.md.
