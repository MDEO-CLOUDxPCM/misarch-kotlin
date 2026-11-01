# Defect fixes: `discount`

Baseline: tag `faithful` (behaviour of the original, docs/specs/discount.md §9). Tests: 29 → 37 green
(`DiscountIntegrationTest` 17, `DiscountValidationIntegrationTest` 9, `DiscountServiceTest` 5, `CouponConcurrencyTest` 5,
`DiscountSdlTest` 1).
SDL unchanged (`DiscountSdlTest`).

## Q1 Usage upsert on non-existent snake_case columns (saga stopped for every order with a discount)

- **Symptom:** every `inventory/product-item/reservation-succeeded` whose order had at least one discount id (and no
  limit violation) failed: `validation-failed` with an empty list, HTTP 500, Dapr redelivered forever, and every
  redelivery published another `validation-failed`. Payment, shipment and invoice never saw such orders; inventory
  released their items. The calibration needed `workarounds/discount_usage_columns.sql`.
- **Root cause:** the native upsert `INSERT INTO DiscountUsageEntity (discount_id, user_id, usages) … ON CONFLICT
  (discount_id, user_id)` names columns the Flyway schema does not have (`discountId`/`userId`).
- **Fix:** the statement uses `discountId`/`userId` and the schema's unique constraint `(discountId, userId)`. It also
  works on a database that has the calibration workaround applied (its sync trigger fills the extra columns), so the
  workaround is no longer needed. `deploy/integration/kseed.py` skips it by default (`KSEED_WORKAROUNDS=1` applies it);
  applied to the live system it is harmless (docs/INTEGRATION.md §9.1).
- **Tests:** `DiscountValidationIntegrationTest`: stock schema → usage inserted, then added (`ON CONFLICT`), then the
  limit check fails the third order; the same with the workaround applied.

## Q2 Any exception in the validation → `validation-failed` + 500 + endless redelivery

- **Symptom:** an exception (DB trigger rejecting a usage, FK violation, …) published `validation-failed []`, answered
  500 and was redelivered forever, publishing one more `validation-failed` per delivery (inventory released the order
  again each time).
- **Root cause:** `catch (e) { publish(failed); throw e }`.
- **Fix:** the usage upserts and the result event run in one transaction (`@Transactional`
  `DiscountUsageService.validateOrder`, called and guarded by `DiscountService.validateOrder`; replaces the handler's
  `@Transactional(REQUIRES_NEW)`). On an exception the transaction is rolled back, `validation-failed` with an
  empty list is published once and the event is acknowledged (200). Only if that publish fails too (Dapr unavailable)
  the exception propagates (500, Dapr redelivers; the usages were rolled back, so a redelivery does not double-count).
- **Tests:** trigger rejection (`check_discount_usages`) → 200, one `validation-failed []`, usages unchanged; FK
  violation → rollback of the earlier upserts, 200; failing publish → 500.

## Q3 `maxUsagesPerUser = null` → NullPointerException

- **Symptom:** a discount without per-user limit (possible via `updateDiscount(maxUsagesPerUser: null)`) made
  `findApplicableDiscounts` (so every `createOrder` with a matching variant) and the order validation fail.
- **Root cause:** `findRemainingUsagesForDiscounts` built the map for all discounts with `maxUsagesPerUser!!`
  although its documentation says it covers only limited discounts; `filterApplicableDiscounts` decremented with `!!`.
- **Fix:** only limited discounts are in the map; unlimited ones are always usable and never decremented.
- **Tests:** `DiscountServiceTest` (unit), `findApplicableDiscounts` with count 1000, validation of an unlimited discount.

## Q4 Unknown id fails the whole data loader batch (NPE)

- **Symptom:** `discount(id)`, `coupon(id)`, `_entities` of Discount/Coupon/DiscountUsage, `Coupon.discount`,
  `DiscountUsage.user`: one unknown id failed every key of the batch.
- **Fix:** libs/common `BaseIdDataLoader` fails missing keys individually (all loaders):
  only the unknown key fails with `NoSuchElementException()` (`Exception while fetching data (/x) : null`, the
  unknown-id convention of the Kotlin originals); the other entities resolve.
- **Tests:** `_entities` with a known and an unknown Discount → first resolved, second null with one error.

## Q6 Non-idempotent, non-transactional replication handlers

- **Symptom:** a duplicate `user/user/created`, `catalog/category/created`, `catalog/product/created`,
  `catalog/product-variant/created` (e.g. redelivery after a timeout) → duplicate key → 500 → redelivered forever.
  The product handler inserted the product and then failed on a link, leaving partial state.
- **Fix:** `INSERT … ON CONFLICT DO NOTHING` for all replica rows (product-category links: `ON CONFLICT (productId,
  categoryId)`), product + links in one transaction.
- **Tests:** duplicates of all four events → 200, unchanged rows; duplicate category ids in one event → one link.

## Q10 Concurrent coupon redemptions lose increments and exceed `maxUsages`

- **Symptom:** concurrent `registerCoupon` calls for one coupon (different users) counted too few usages and could all
  succeed beyond `maxUsages`: 40 parallel redemptions left `usages = 4`; with `maxUsages = 10` all 40 succeeded.
- **Root cause:** the trigger `update_coupon_usages` (BEFORE INSERT ON CouponRedemptionEntity, V1_1) read `usages,
  maxUsages` with a plain SELECT (no lock), added 1, checked the limit and wrote the computed value back. Under READ
  COMMITTED two redemptions both read n before either committed; the second UPDATE waited for the first's row lock and
  then wrote n + 1 as well. `updateCoupon` is not involved: it saves the loaded coupon, but `CouponEntity.usages` is
  `@ReadOnlyProperty`, so its UPDATE does not contain `usages`.
- **Fix:** migration `V1_3__atomic_coupon_usages.sql` replaces the function (V1_1 stays byte-identical for its Flyway
  checksum, like the other fixes' migrations): `UPDATE CouponEntity SET usages = usages + 1 WHERE id = NEW.couponId
  RETURNING usages, maxUsages INTO …`, then `RAISE EXCEPTION 'Coupon has been used too often'` if over the limit; the
  exception rolls the increment back with the insert. A redemption waiting for a concurrent one now increments the
  committed count. Same message, same result for an unknown coupon (no row, then the foreign key fails) and for
  sequential redemptions. One difference: a rejected redemption now also takes (and releases) the coupon row lock, so
  it waits for a concurrent redemption of that coupon like a successful one always did.
- **Tests:** `CouponConcurrencyTest`: 40 parallel `registerCoupon` without a limit → 40 redemptions, `usages = 40`,
  distinct counts in the answers; with `maxUsages = 10` → exactly 10 succeed, 30 fail with the trigger message,
  `usages = 10`; two interleaved JDBC transactions (the second waits for the first's coupon row) → `usages = 2`, and
  with `maxUsages = 1` the second is rejected and rolled back; `updateCoupon` whose UPDATE waits for an uncommitted
  redemption keeps the incremented `usages`. On the V1_1 trigger the first four fail (`usages` 4 of 40; 40 of 40
  succeed with limit 10; `usages` 1 of 2; the over-limit second redemption succeeds); without `@ReadOnlyProperty` the
  `updateCoupon` test fails (`usages` reset to 0).

## cvc lost in the re-published order

- **Symptom:** the order published `paymentAuthorization: {"cVC": 123}`; discount read the missing `cvc` as 0 and
  re-published `{"cvc": 0}` to payment and invoice.
- **Fix:** order now publishes `{"cvc": 123}` (docs/fixes/order.md); discount reads `cvc` and still accepts the
  former key `cVC` (`@JsonAlias`), so the value is kept.
- **Payload change:** none in discount's output format (`{"cvc": n}` as before), only the value is now correct.
- **Tests:** both keys → re-published `{"cvc":123}`.

## Not fixed (deliberately)

- Q5 coupon validity window / `maxUsages` not checked in `findApplicableDiscounts`: redemption is checked at
  `registerCoupon` (strict validity, trigger for `maxUsages`); whether an expired-but-redeemed coupon may still be used
  is a business rule the original does not state.
- Q6 (rest) events published inside the DB transaction (before commit): standard pattern of all Kotlin originals,
  not observable as wrong results.
- Q7 `hasNextPage` probe without ORDER BY: the existence of a row at an offset does not depend on the order.
- Q8 `DiscountUsage.usages` bigint exposed as `Int`: SDL type.
- Q9 `discountRequiresCoupon` empty without a header: intentional authorization filter.
- Redelivered `reservation-succeeded` (after a lost acknowledgement) counts the usages twice: needs a record of
  processed orders; Dapr only redelivers acknowledged events on failure, and with Q1/Q2 fixed the handler no longer
  fails systematically.
- The `_entities` stub resolvers (Category/Product/ProductVariant/User) do not check existence: federation stubs.
