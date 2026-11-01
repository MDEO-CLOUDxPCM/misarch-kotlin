# Defect fixes: `return`

Baseline: tag `faithful` (behaviour of the original, docs/specs/return.md §7). Tests: 18 → 18 green
(`ReturnIntegrationTest` 10, `ReturnServiceTest` 7, `ReturnSdlTest` 1; tests that asserted the defects now assert the
fixed behaviour, the "always fails" test became an end-to-end return test). SDL unchanged (`ReturnSdlTest`).

Contract changes: `/dapr/subscribe` has a fourth entry (`catalog/product-variant-version/created`,
route `/subscription/catalog/product-variant-version/created`, the SDK convention of the other routes; a new
subscription to an existing topic, the experiment-config sidecar rewrites it like the others). The handler of
`shipment/shipment/status-updated` now also reads `orderId` (already in shipment's payload). Published payloads unchanged.

## Q1 The PVV replica is never fed: every `createReturn` fails (quirk 1)

- **Symptom:** the return flow never worked: every `createReturn` that passed the earlier checks failed with
  `Product variant version not found`; `return/return/created` was never published, so shipment never created return
  shipments (T34 never fired). Load: every return attempt was a failing mutation and no return-side work happened.
- **Root cause:** `EventController.onProductVariantVersionCreated` had neither `@Topic` nor `@PostMapping`.
- **Fix:** `@Topic("catalog/product-variant-version/created")` + `@PostMapping("/subscription/catalog/product-variant-version/created")`,
  transactional. The insert is an idempotent upsert (`ON CONFLICT (id) DO UPDATE SET canBeReturnedForDays`).
  `canBeReturnedForDays` is optional in the DTO (catalog omits it when null; null = returnable indefinitely, catalog SDL).
  Payload assumption: catalog's real event (`reference/events/catalog_product-variant-version_created.json`) carries
  `id` and `canBeReturnedForDays` (30 in the calibration data); all other properties are ignored.
- **DB triggers checked (no migration needed):** `before_update_orderitem_check_return_order_match` fires on every
  UPDATE of `OrderItemEntity` but only checks rows whose `returnedWithId` is set (return of the same order: always true
  for `createReturn`, which validates the order first). `check_return_trigger` (`BEFORE UPDATE OF returnedWithId`)
  fires only for statements that list `returnedWithId` in their SET list: the full-row save in `createReturn`, not the
  `ON CONFLICT … SET sentWithId/compensatableAmount` upserts. In `createReturn` it repeats the service's checks
  (delivered, return period) inside the same transaction, where the new `ReturnEntity` is visible; with the PVV and a
  delivered shipment replicated it passes. So neither trigger blocks the intended flow or unrelated updates; they stay
  as they are (V1_1 untouched).
- **Tests:** `a buyer can return a delivered order item end to end` (PVV event with the real payload shape →
  order/created → shipment/created PENDING → status-updated DELIVERED → `createReturn` succeeds, publishes
  `return/return/created`; then the return shipment's created/status events are acknowledged); the other return
  tests now replicate the PVV through the event instead of SQL; the trigger test still passes; `/dapr/subscribe` test
  (reference + the PVV entry, redelivery 200, omitted `canBeReturnedForDays` → NULL).

## Q2 Non-transactional, non-idempotent event handlers (quirk 8)

- **Symptoms:** (a) `shipment/shipment/created` processed before `order/order/created` (independent topics): the
  shipment row was inserted, the item upsert failed on the FK → 500, and every redelivery then failed on the duplicate
  shipment id: the order's items never got `sentWithId`, so they could never be returned. (b) A duplicate
  `order/order/created` (redelivery) → PK violation → 500 forever. (c) `shipment/shipment/status-updated` DELIVERED of
  a **return shipment** (never registered here) → `NoSuchElementException` → 500 → Dapr redelivered it forever: with
  working returns this would be one endless redelivery loop per return (load!).
- **Root cause:** plain INSERTs, no transaction, `findById(...).awaitSingle()` for any shipment.
- **Fix:** the multi-statement handlers (`registerShipment`, `registerOrder`) are `@Transactional` service methods (a
  failure rolls back everything, the redelivery starts clean); the single-statement ones need no transaction.
  `INSERT OrderEntity … ON CONFLICT (id) DO NOTHING`; `INSERT ShipmentEntity … ON CONFLICT (id) DO UPDATE SET
  deliveredAt = COALESCE(existing, new)`. `status-updated` DELIVERED: with `orderId` → upsert `deliveredAt = now`
  (so a DELIVERED that overtakes `shipment/created` is not lost), without `orderId` (return shipments; shipment omits
  the property) → `UPDATE … WHERE id` (no-op if unknown). Non-DELIVERED statuses stay no-ops. All return 200.
- **Tests:** `order and shipment events replicate the data, handlers are transactional and idempotent` (duplicate
  order 200, shipment redelivery keeps `deliveredAt`, return-shipment DELIVERED/IN_PROGRESS 200 without row, DELIVERED
  before created, shipment before order → 500 with nothing written, then 200 after the order arrived).

## Q3 Empty / unknown `orderItemIds` → "List is empty." (quirk 2)

- **Symptom:** `createReturn` with an empty list or only unknown ids failed with `List is empty.` (from `first()`); the
  intended messages were dead code.
- **Fix:** the non-empty check (`At least one order item id must be provided`) and the existence check
  (`Order item ids [..] do not exist`) run first, then ownership and the remaining checks in the original order with
  the original messages. Consequence for quirk 3: a foreign buyer with partly unknown ids now gets the existence
  message instead of the ownership message (both are errors; ownership is still checked before any state is revealed
  beyond existence of ids).
- **Tests:** `ReturnServiceTest` (`empty or unknown ids give the intended messages`, `ownership is checked after
  existence …`), `createReturn validation errors`.

## Q4 Unreplicated shipment → NullPointerException in the return-period check

- **Symptom:** an item whose `sentWithId` shipment row was missing passed the "delivered" check (missing shipments were
  ignored) and then failed with an NPE (`shipmentsById[...]!!`) when the PVV had a return period.
- **Fix:** a missing shipment counts as not delivered (`At least one order item was not delivered`). With Q2 the case
  can no longer arise from the handlers (shipment row and `sentWithId` are written atomically).
- **Tests:** `ReturnServiceTest` `undelivered shipment and mixed orders`.

## Q5 `returns` / `User.returns` without header → NullPointerException (quirk 4)

- **Fix:** both resolvers require the header (`dfe.authorizedUser`): `Exception while fetching data (/returns) :
  Unauthorized access` (and `/_entities[i]/returns`), the error of every other authenticated field. The connection's
  filter throws the same error instead of `!!` as a safety net. A non-employee asking for another user's returns still
  gets an empty connection (unchanged).
- **Tests:** `returns connection paging and ordering, anonymous requests fail`.

## Q6 `_entities(Return)` has no resolver (quirk 6)

- **Symptom:** `Return` is a resolvable `@key` in the SDL, but every `_entities` lookup failed (`Unable to resolve
  federated type`), e.g. a gateway query `Shipment.return { reason refundedAmount }` (shipment owns the `Return.shipment`
  extension and returns only the stub).
- **Fix:** `ReturnResolver` via `ReturnDataLoader` (batched), no authorization (like `OrderItem.returnedWith`, which
  already returns the same object without auth). Null id → null; unknown id → null entry + error.
- **Tests:** in `createReturn with a replicated PVV …`: known and unknown Return representations.

## Q7 `ReturnDataLoader`: unknown id fails the whole batch

- **Fix:** libs/common `BaseIdDataLoader` (every loader): only the unknown key fails (`… : null`).
- **Tests:** covered by the `_entities(Return)` test (known + unknown in one batch).

## Not fixed (deliberately)

| Item | Reason |
|---|---|
| Quirk 3: ownership only via the first item's order; employees cannot create returns for a buyer | The same-order check covers the other items; "Only the original buyer can create returns" is an explicit rule of the original. |
| Quirk 5: `Query.return` reads the DB before the authentication check | Only the order of two errors for an anonymous request with an unknown id; no data is revealed. |
| Quirk 7: `refundedAmount` narrowed to GraphQL `Int` | SDL type; a fix needs an SDL change. Amounts above 2^31 do not occur in the workload. |
| Quirk 9: `deliveredAt` = processing time of the event | The status event carries no delivery time (shipment payload); seconds of difference for a period counted in days. |
| Quirk 10: `Return.returnedItems` / `OrderItem.returnedWith` without auth | API convention of the original (federated fields resolved through the gateway). |
| Quirk 11: `CommonOrderInput` "Discount order" description, ignored `field` | SDL description; the only field is `ID`. |
| Publish inside the `createReturn` transaction (commit failure after publish) | Project-wide convention of the Kotlin services (PORTING.md §5). |
| `upsertOrderItemFromShipment` overwrites `sentWithId` with the latest shipment | Shipment creates one shipment per item and order; harmless. |
