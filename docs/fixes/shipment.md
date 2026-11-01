# Defect fixes: `shipment`

Baseline: tag `faithful` (behaviour of the original, docs/specs/shipment.md §8). Tests: 17 → 18 green
(`ShipmentIntegrationTest` 14, `FeeCalculationTest` 3, `ShipmentSdlTest` 1). SDL unchanged (`ShipmentSdlTest`).
Unchanged contracts: app id, topics, `/dapr/subscribe`, subscription routes, `POST /shipment/{id}/status`, the provider
request body, the ECS variables. Payload change: `shipment/shipment/creation-failed` may now omit `shipmentMethodId`
and/or `shipmentAddressId` (NON_NULL) - only in the new return-path failure events (Q4), where they are not known;
the event has no effective consumer (spec §5.4).

## Q5 Non-idempotent replica inserts (address, product variant version)

- **Symptom:** a redelivered or duplicate `address/user-address/created`, `address/vendor-address/created` or
  `catalog/product-variant-version/created` → PK violation → HTTP 500 → Dapr redelivered it forever.
- **Fix:** `INSERT … ON CONFLICT (id) DO NOTHING` (the first version of the row wins; these events are creations of
  immutable rows).
- **Tests:** `address and product variant version events are stored idempotently, …` (user/vendor address and PVV
  redeliveries → 200, rows unchanged).

## Q-dup Redelivered `payment-enabled` / `return/created` creates a second set of shipments

- **Symptom:** every redelivery of `payment/payment/payment-enabled` (e.g. after a 500 from a later step, or a Dapr
  redelivery of a message still pending because the provider was slow) created and registered all shipments of the
  order again and published `shipment/created` again (inventory/return then saw two shipments per item; the simulation
  delivered both). The same for `return/return/created`.
- **Fix:** the transactions that store the shipments of an order / a return (`ShipmentService.createShipmentsForOrder`,
  `createShipmentForReturn`) first take a transaction-scoped advisory lock (`ShipmentRepository.lockForTransaction`)
  (`pg_advisory_xact_lock(hashtext('order:<id>'|'return:<id>'))`) and return without effect if shipments for the order
  (`existsByOrderId`) / the return (`existsByReturnId`) exist. The lock serializes concurrent deliveries of the same
  message; it is released at commit, before the provider registration (Q2), so a second delivery sees the stored
  shipments and registers nothing. An order whose shipment groups all failed (`creation-failed`) has no shipment, so a
  redelivery would try again (the original acknowledged that event, so this only happens on Dapr-level duplicates).
- **Tests:** redelivered `payment-enabled` (no new rows, no provider call, no event); redelivered `return/created`;
  `concurrent duplicate payment-enabled deliveries create the shipments once` (two parallel deliveries, slow provider).

## Q1 Failed creation leaves a phantom PENDING shipment

- **Symptom:** an unknown product variant version (`Not all product variant versions are valid`) or a provider failure
  after all retries was caught after the shipment, its order items and join rows had been inserted; the transaction
  committed a PENDING shipment that the provider never knew (it stayed PENDING forever, `Order.shipments` showed it,
  `OrderItem.sentWith` pointed to it) while `creation-failed` was published for it.
- **Root cause:** inserts before the fallible steps, `catch (Exception)` without undo.
- **Fix:** the checks run before the first insert, in the original order: address exists, shipment method,
  quantity/weight (PVVs), address load; a failed check stores nothing and publishes `creation-failed`. The shipment,
  its order items and join rows are then stored, the shipment is registered at the provider (outside the
  transaction, Q2) and `shipment/created` is published. A failed registration removes the shipment, the order items
  it stored and its join rows again and publishes `creation-failed` (`ShipmentService.discardShipment`, one
  transaction). Same error messages and `creation-failed` payloads as before. Residual: a crash between the commit of
  the shipment and its registration leaves a PENDING shipment that the provider does not know (a redelivery sees the
  shipment and does nothing).
- **Tests:** `failures during shipment creation publish creation-failed` (unknown PVV → no shipment, no order item);
  `provider retries follow the experiment-config variables` (always failing provider → shipment, join row and order
  item removed, all attempts with the same shipment id).

## Q2 The provider call holds the transaction

- **Symptom:** the provider HTTP call (with retries and delays) ran inside the handler's transaction, so a slow or
  failing provider held a pool connection (default pool 10) for up to `providerRetries × providerRetryDelay` plus the
  request times - relevant for experiments that inject provider failures.
- **Fix:** like order's checkout, the remote call is not part of a transaction: `ShipmentRegistrationService` stores
  the shipments in one short `ShipmentService` transaction (lock, idempotency check, inserts), registers each one at
  the provider (`ShipmentProviderClient`, retries of the experiment configuration) and publishes `shipment/created`;
  a failed registration is undone in a second short transaction (Q1). The shipment is committed before the provider
  knows it, so a provider callback can no longer overtake the creating transaction.
- **Tests:** `concurrent duplicate payment-enabled deliveries create the shipments once`, `provider retries follow the
  experiment-config variables`.

## Q4 Return-path errors → 500 and endless redelivery instead of `creation-failed`

- **Symptom:** `return/return/created` with no vendor address, no (active) shipment method, no known order item or an
  unknown PVV threw outside the `try`: HTTP 500, Dapr redelivered forever (load), no `creation-failed`.
- **Fix:** these errors publish `shipment/shipment/creation-failed` like the order path (`returnId`, `orderItemIds` of
  the event, `reason`; `shipmentAddressId`/`shipmentMethodId` when already known) and the event is acknowledged (200).
  If that publish itself fails, the handler fails (500, redelivery), as on the order path.
- **Tests:** `return shipment without any vendor address publishes creation-failed and is acknowledged` (exact JSON
  `{"returnId":…,"orderItemIds":[…],"reason":"No vendor address found"}`); unknown order items only → 200 +
  `creation-failed` (`orderItems must not be empty`, with vendor address and method).

## Q3 Archived shipment methods are used (partly fixed)

- **Symptom:** the SDL says an archived method "can no longer be used", but `calculateShipmentFees` (called by order's
  `createOrder`), the order shipments and the return-shipment selection all accepted archived methods (the oracle run
  picked an archived method for a return shipment).
- **Fix:**
  - return-shipment selection (`findLeastExpensiveShipmentMethod`) considers only methods with `archivedAt IS NULL`
    (none left → `No shipment methods available` → `creation-failed`, Q4);
  - `calculateShipmentFees` rejects an archived method like an unknown one (`Not all shipment methods are valid`), so
    order's `createOrder` fails for an order using an archived method (order discards the fee result but fails on the
    error - intended: no new orders with a method that can no longer be used). The seeded/calibration methods are
    never archived, so the workload is unaffected;
  - **not changed:** shipments for `payment-enabled` still accept an archived method - the order was accepted and paid
    before the method was archived, refusing to ship it would strand the order.
  - `ShipmentMethod.calculateFees` (a pure computation on one method) still computes for archived methods.
- **Tests:** `fee calculation matches the original` (archived method → error; the remaining fee unchanged, 1262);
  return test picks the cheapest *active* method although a cheaper archived one exists.

## Q7 Unknown id fails the whole data loader batch; `Return.shipment` NPE

- **Fix:** libs/common `BaseIdDataLoader` (all loaders): only the unknown key fails (`… : null`; in `_entities` graphql-kotlin's
  `Exception was thrown while trying to resolve federated type, …`). `Return.shipment` (SDL `Shipment!`) uses
  `findFirstByReturnId` and throws `NoSuchElementException()` for a return without shipment: `… : null` instead of
  `Cannot invoke "…ShipmentEntity.toDTO()"`; a (legacy) second row no longer fails.
- **Tests:** `_entities` with a known and an unknown Shipment; unknown `Return.shipment` → `… : null`.

## Q13 `ShipmentFilterInput.status` never works

- **Symptom:** `Order.shipments(filter: {status: …})` always failed (`Cannot encode parameter of type
  io.r2dbc.spi.Parameters$InParameter (PENDING)`) - the order tracking page could not filter.
- **Fix:** the filter binds `status.name` (the column stores the name).
- **Tests:** `payment enabled creates …`: `PENDING` → both shipments, `DELIVERED` → none.

## Not fixed (deliberately)

| Item | Reason |
|---|---|
| Order shipments accept archived methods | See Q3 (the order was accepted before the archiving). |
| Q6: no authorization on `Order.shipments`, `Shipment.*`, `shipmentMethods` | API convention of the original. |
| Q8: re-archiving republishes `archived`; status updates allow any transition | Convention; the provider is the authority for the status. |
| Q9: Int overflow / truncation in the fee formula, `count.toInt()` | Fee semantics of the original; no overflow with realistic values. |
| Q10: mutation `archivedAt` in-memory vs DB precision | Cosmetic. |
| Q11: SDL descriptions with typos | SDL must stay identical. |
| Q12: `OrderItemEntity.sentWithId` stays the first shipment | One shipment per item and order; harmless. |
| `POST /shipment/{id}/status` for an unknown id → 500 | An unknown (or removed, Q1) shipment; the simulation retries 3 × 5 s and gives up. |
| `creation-failed` has no consumer | Saga-level gap of the original (order never compensates on it); outside this service. |
