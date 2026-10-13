# order: fixed defects

Baseline: tag `faithful` (docs/specs/order.md §12). Tests: 35 → 36 green (`OrderGraphQLIntegrationTest` 11,
`OrderEventIntegrationTest` 9, `CreateOrderLogicTest` 8, `RustCompatTest` 5, `TransportFailureTest` 2, `OrderSdlTest` 1).
SDL unchanged. No new tables or migrations.

## 1. Order amounts were wrong (payment charged the wrong total)

- **Symptom:** `compensatableAmount = trunc(price × Π discount)`: a 10 % discount charged 10 % of the price, an item
  without discount the full unit price whatever the count; `compensatableOrderAmount` (the amount payment charges and
  the provider gets) ignored counts and shipment fees. The `orderAmount` sent to discount for `minOrderAmount` was the
  u32 sum of unit prices without counts.
- **Root cause:** `calculate_compensatable_amount` multiplies by the discount fraction and has no count;
  `calculate_order_amount` sums unit prices; the result of `query_shipment_fees` is bound to `_shipment_fees` and
  dropped (the function's own doc says "Applies fees and discounts", and `calculate_compensatable_order_amount` is
  "the total compensatable amount that the buyer needs to pay").
- **Fix:** item amount = `round(price × count × Π(1 − discount))` (discount's own rule: `0 ≤ d ≤ 1`, "a discount
  cannot increase the price"); order amount = Σ item amounts + the shipment fees of the order (one `calculateShipmentFees`
  call as before); `orderAmount` = Σ price × count (saturating at `Int.MAX_VALUE`, discount's GraphQL `Int`).
  The retail price is taken as the gross price (no tax added), as before.
- **Effect on load:** none on the number of calls; amounts in events/payment/invoice change.
- **Tests:** `CreateOrderLogicTest` (rounding, count, stacked discounts, saturation), `createOrder` integration
  (20 × 2 × 0.5 + 30 + fees 5 = 55, discount `orderAmount` 70).

## 2. `paymentAuthorization` key `cVC` (CVC lost downstream)

- **Symptom:** `order/order/created` carried `{"cVC": 123}` (serde enum naming); discount read `cvc` as 0 and
  re-published `{"cvc": 0}`; invoice could not deserialize it at all.
- **Fix / payload change:** `paymentAuthorization` is the documented `{"cvc": n}` (or `null`). Consumers: discount
  (reads `cvc`, still accepts `cVC`), inventory (passes through), payment (stores raw), shipment (`cvc`), invoice
  (now accepts it), return (ignores it).
- **Tests:** OrderDTO unit test and the golden test against `reference/events/order_order_created.json` (expected with
  `cvc`), `placeOrder` integration.

## 3. `catalog/product-variant/updated` always 422 (redelivered forever, visibility never replicated)

- **Symptom:** every variant update event was answered 422 and redelivered by Dapr forever (load), visibility never
  reached order.
- **Root cause:** expected `isPubliclyVisible: String`, catalog publishes `{"id", "publiclyVisible": bool}`.
- **Fix:** accepts `publiclyVisible` or `isPubliclyVisible`, as a boolean or `"true"`/`"false"`, and sets the
  boolean column (unknown variant: no-op 200, as before). The "string corrupts the replica" path is gone. Invisible
  variants are now really dropped from new orders (original behaviour of `createOrder`, see "not fixed").
- **Tests:** `OrderEventIntegrationTest` (all key/value forms, 422 for a non-boolean, a later version keeps the visibility).

## 4. Address validation only checked the user

- **Symptom:** any address id (another user's, archived, unknown) was accepted for shipment and invoice.
- **Root cause:** `validate_user_address` queried `{_id: userId}` only (its doc: "Checks if an address is registered
  under a specific user").
- **Fix:** both addresses must be in the user's replicated `user_address_ids`; the user and its address ids are read
  with one query (same round trips as before). To make the replica reliable, `address/user-address/created` is now an
  idempotent upsert (no duplicate entry on redelivery; an address event that arrives before the user event creates the
  user row instead of being dropped); `user/user/created` keeps an existing row (fix 6).
- **Tests:** foreign, unknown and archived addresses → `User address with UUID … not found.`; early address event.
- **System run:** createOrder of seeded users must succeed (their addresses were replicated during seeding).
  Verified on a fresh stack (docs/INTEGRATION.md §9): all saga and loadgen orders of seeded users succeeded.

## 5. `orderItem(id)` / `_entities(OrderItem)` always failed

- **Root cause:** the original read the never-written collection `order_items`.
- **Fix:** the item is read from the order that contains it (one query); `orderItem` authorizes against the order's
  user (as the original code intended), the entity resolver does not authorize (like the other entities).
- **Tests:** query (owner, foreign buyer, unknown id), `_entities` OrderItem.

## 6. Non-idempotent replica events (500 forever)

- **Symptom:** duplicate `user/user/created` (e.g. the double publish of user/Keycloak), `discount/coupon/created`,
  `shipment/shipment-method/created` → duplicate key → 500 → redelivered forever (the calibration acked them with a
  reaper). A concurrent/redelivered first `product-variant-version/created` could also hit a duplicate key.
- **Fix:** `INSERT … ON CONFLICT DO NOTHING`; the product-variant version is one atomic upsert.
- **Tests:** duplicates → 200, replica unchanged.

## 7. Compensation never ran, and its check was inverted

- **Symptom:** `shipment/shipment/creation-failed` was not subscribed (route served but not in `/dapr/subscribe`), so a
  failed shipment never produced a compensation; called directly, `verify_items_uncompensated` failed whenever ANY
  existing compensation shared no item with the event and re-compensated the same items on redelivery.
- **Fix / contract change:** `/dapr/subscribe` additionally lists
  `{"pubsubName":"pubsub","topic":"shipment/shipment/creation-failed","route":"/on-shipment-creation-failed-event"}`
  (existing topic and route). A failed **return** shipment (`orderId` null/missing, which the shipment fix now
  publishes) is acknowledged without action; only items of the order that are not compensated yet are compensated;
  an event whose items are all compensated (redelivery) is acknowledged without a second compensation.
- **Payload change:** `order/order-compensation/created` is the documented
  `{id, orderId, orderItemIds, triggeredAt, amountToCompensate}` (was `{id, amount_to_compensate}`); the topic has no
  subscriber in MiSArch.
- **Tests:** compensation of unrelated items, redelivery, return shipment, payload; `/dapr/subscribe`.

## 8. placeOrder: rejection of PLACED orders, double placement

- **Symptom:** `placeOrder` of an order older than 1 h set REJECTED even if it was already PLACED (the shipped order
  became REJECTED); two concurrent placements could both succeed and publish `order/order/created` twice (double
  reservation, payment, shipment).
- **Fix:** both transitions are compare-and-set on `PENDING` (`UPDATE … WHERE order_status = 'PENDING'`); an order
  that is not PENDING gets the original "must be `OrderStatus::Pending`" error.
- **Tests:** old PLACED order stays PLACED; 4 concurrent placements → 1 success, 1 event.

## 9. `User.orders` skip off-by-one

- **Symptom:** with `skip > 0` and `skip + first + 1 == totalCount` the page held `first + 1` nodes and
  `hasNextPage = false` (mongodb-cursor-pagination 0.3.2).
- **Fix:** `nodes = first` rows, `hasNextPage = fetched > first` for every skip. Default page size 25 and the `id DESC`
  tiebreak are kept (library conventions, not wrong results).
- **Tests:** the former quirk case and the true last page.

## Not fixed (deliberately)

- Invisible or unreplicated product variants are silently dropped from the order (zero-item orders possible): explicit
  filtering code of the original (`query_product_variants_by_product_variant_ids`); whether this should be an error is
  a business decision. With fix 3 visibility changes now reach order, so the path is reachable.
- `inventory/product-item/reservation-failed`, `discount/order/validation-failed` and `payment/payment/payment-failed`
  do not update the order (it stays PLACED; `RejectionReason.INVENTORY_RESERVATION_FAILED` is never set): the original
  has no such subscription and the docs do not list one - a missing feature, not a defect of existing code.
- No version check for product-variant versions / tax-rate versions (the last processed event wins): needs a version
  column; Dapr delivers a topic mostly in order.
- `{"status":0}` event answers, Rust type names in messages, `User.orders` default page size 25 and tiebreak: conventions.
- The status change of `placeOrder` is committed before the publish (a publish transport failure leaves a PLACED order
  without event): would need an outbox.
