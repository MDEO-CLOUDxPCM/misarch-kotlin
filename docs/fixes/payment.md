# payment — defect fixes

The faithful port (commit `060498d`, spec `docs/specs/payment.md`) reproduced the defects of the original NestJS
service. This document lists the defects fixed since then, and those deliberately left as they are. The federation
SDL, Dapr app-id, topics and the provider callback path are unchanged
(`PaymentSdlTest` / `tools/sdl-diff.sh payment` empty).

Since the unification (Kotlin entities/repositories/services, docs/specs/payment.md §12) the fixes are covered by
`PaymentIntegrationTest` and `CreditCardValidatorTest` (`tools/build.sh :services:payment:test`); the class-validator
corpus test is gone with the emulation. Processing is now awaited in the handlers (owner decision O6, P12 below).

## Fixed

### P1 — payment start failure crashed the service

- **Symptom:** an `order-validation-succeeded` event with an unknown `paymentInformationId` (or a non-numeric
  `compensatableOrderAmount`, or a database error) published `payment-failed` and then terminated the process
  (spec D1): the clean-up `openOrders.delete(order.id)` looks up `paymentId == order.id`, never matches and rejects.
  Every start failure restarted payment (lost in-flight work, restart time under load). A failure after the payment
  insert left an orphan OPEN payment.
- **Root cause:** clean-up by the wrong key, not awaited; payment and open order created in two steps.
- **Fix:** payment and open order are created in one transaction (`PaymentService.createPayment`); an unknown
  payment information or an invalid order stores nothing and publishes `payment-failed {order}`. The provider
  registration and `payment-enabled` follow outside the transaction (`PaymentRegistrationService`); if one of them
  fails, the payment and its open order are removed again (`PaymentService.discardPayment`) and the event fails, so
  Dapr redelivers it and the redelivery starts over.
- **Tests:** `an order without a valid payment information or amount publishes payment-failed and stores nothing`,
  `a failing provider registration fails the event and stores nothing, the redelivery succeeds`.
- **Contract:** none (`payment-failed` payload unchanged).

### P2 — failed credit-card payments hung forever (spec quirks 4 + 7)

- **Symptom:** the simulation answers 5 % of payments with `FAILED`. For a credit card the original "retried" with
  `POST ${SIMULATION_URL}/register` `{paymentId, type}` (no such route, 404), never stored `numberOfRetries`, so the
  limit of 3 never triggered: the payment stayed PENDING forever, the provider never processed the retry, no
  `payment-failed`. GraphQL always showed `numberOfRetries: 0`.
- **Root cause:** wrong endpoint/body in `CreditCardService.update`, counter never written.
- **Fix:** `numberOfRetries` is stored (0 at creation, migration `V2__fixes.sql` sets old NULLs to 0). A FAILED
  callback with fewer than 3 retries increments it (the payment row is locked, `SELECT … FOR UPDATE`) and registers
  the payment again with `POST ${SIMULATION_URL}/payment/register` `{"paymentId","amount","paymentType":"credit-card"}`
  (`amount` = stored `totalAmount`); status stays PENDING. The 4th FAILED sets FAILED (only on the transition) and
  publishes `payment-failed {order}`. A SUCCEEDED callback after a retry is stored as usual.
- **Load effect:** ~5 % of credit-card payments now cause another provider round trip (each retry fails again with
  5 %), i.e. ~1.05 register calls/callbacks per credit-card payment; ~0.0006 % end FAILED with `payment-failed`
  (inventory then releases the items).
- **Tests:** `failed credit card payments are retried three times, then fail and publish payment-failed once`,
  `a credit card payment that succeeds on a retry is stored as succeeded`.
- **Contract:** the retry request now goes to `payment/register` (the simulation's existing route).

### P3 — successful prepayments were never stored (spec quirk 8)

- **Symptom:** prepayment `SUCCEEDED` published `payment-enabled` but left the payment PENDING without `payedAt`;
  7 days later the cron set it FAILED and published `payment-failed`, so inventory released the already shipped
  items. Wrong state in GraphQL, phantom failures.
- **Root cause:** `PrepaymentService.update` returned without updating the status.
- **Fix:** SUCCEEDED is stored with `payedAt`; `payment-enabled` is published only when the status actually changes
  (a repeated callback publishes nothing).
- **Tests:** `a prepayment enables the order on success, once, and is not failed by the job afterwards`.

### P4 — no idempotency for redelivered events (spec quirk 11)

- **Symptom:** every redelivery of `user/user/created` added two more payment informations; a redelivered
  `discount/order/validation-succeeded` created a second payment for the same order, registered it at the provider
  again and published a second `payment-enabled` (double shipment work downstream).
- **Fix:** `user-created`: one transaction with a per-user advisory lock (`pg_advisory_xact_lock`, repository
  `@Query`); a default method
  (PREPAYMENT, INVOICE) the user already has is not created again. `validation-succeeded`: the open order stores the
  order id in the new unique column `OpenOrderEntity.orderId`; it is inserted first in the creation transaction
  (`ON CONFLICT DO NOTHING`); a conflict means the order already has a payment and the event is ignored (no payment,
  no register, no events). Both are safe under concurrent delivery (a concurrent second delivery waits for the
  creation transaction and then sees the open order). The order id is required (event DTO).
- **Tests:** `user created adds prepayment and invoice once, also when redelivered concurrently`, `a redelivered
  validation-succeeded event creates no second payment` (sequential, concurrent, and concurrent first deliveries).
- **Contract:** none. Migration `V2__fixes.sql` (new column + unique index); since V3 the open order's key is the
  payment id.

### P5 — failed prepayments released nothing (found during the fix work)

- **Symptom:** prepayment `FAILED` from the provider (5 %) only set the status. The payment-failed path (inventory
  releases the reserved items) never ran: the 7-day cron only selects PENDING payments. The items stayed RESERVED
  forever.
- **Root cause:** `PrepaymentService.update` handles only SUCCEEDED specially; the documented `payment-failed`
  ("published when the payment fails finally") was only wired to the cron and the credit-card limit.
- **Fix:** FAILED is stored and, on the transition, publishes `payment-failed {order}`.
- **Tests:** `a failed prepayment publishes payment-failed once, an unpaid one is failed by the job after 7 days`.
- **Contract:** additional `payment/payment/payment-failed` events (same payload). Invoice FAILED is unchanged (no
  event; the original says explicitly "no relevant events" for invoices, and the order is already enabled).

### P6 — fragments under connections (spec quirk 6)

- **Symptom:** an inline fragment under `PaymentConnection`/`PaymentInformationConnection` failed with
  `Cannot read properties of undefined (reading 'value')`; a fragment spread left `totalCount`/`nodes` uncomputed
  (`Cannot return null for non-nullable field …totalCount.`).
- **Fix:** each connection field runs its own statement when it is resolved (`ConnectionQuery`), however it is
  selected.

### P7 — `user` filter never matched, buyers could not delete their own information (spec quirk 1)

- **Symptom:** `Query.paymentInformations(filter: {user})` always returned nothing (string compared with the stored
  `{id}` object); `deletePaymentInformation` compared `"[object Object]"` with the caller id, so buyers always got
  Unauthorized for their own information.
- **Fix:** the filter matches `userId`; the owner check compares the stored owner id.
- **Tests:** `payment informations are visible to their owner and employees, filtered and ordered`,
  `buyers delete their own payment informations, employees any`.

### P8 — `orderBy` without defaults (spec quirk 5)

- **Symptom:** `orderBy: {field: ID}` → `Invalid sort value: { _id: undefined }`; `{}` → error; `{direction}` only →
  unsorted (natural order).
- **Fix:** `field` defaults to `ID`, `direction` to `ASC` (the only field; same defaults as the other services).
- **Tests:** integration (ordering with partial `orderBy`).

### P9 — `deletePayment` returned an unpopulated payment (spec quirk 10)

- **Symptom:** after a successful delete, selecting `paymentInformation { … }` failed and nulled `data`.
- **Fix:** the deleted payment's information is resolved by its field (data loader; the information is not deleted).
- **Tests:** in `payments are filtered, resolved as entities and deleted by admins`.

### P10 — card mask and leaked card data (spec quirk 2)

- **Symptom:** `"4111 1111 1111 1111"` stayed unmasked in `publicMethodDetails` (the regex masked only digits followed
  by 4 contiguous digits); the provider-callback answer contained `secretMethodDetails` (raw card number).
- **Fix:** every digit except the last four is masked (`"**** **** **** 1111"`, separators kept); the callback has no
  answer body any more (`secretMethodDetails` is still stored, as the original's design intends).
- **Tests:** `CreditCardValidatorTest` (masking); integration (masked public details).

### P11 — expiration validator accepted garbage (spec quirk 3)

- **Symptom:** `"abc"`, `"12"`, `"05-30"`, `"12/30abc"`, … were valid expiration dates (comparisons with NaN are false).
- **Fix:** the documented format `MM/YY` or `MM/YYYY` is enforced (1–2 digit month, whitespace around the parts
  allowed), then the original range checks.
- **Tests:** `CreditCardValidatorTest` table, including the inputs the original accepted (`+5/30`, `05-30`, `abc`,
  `12`, `12/`, `/30`, empty, `12/30abc`, `1e1/30`, …). The seeder's `12/30` is valid.

### P12 — fire-and-forget processing (spec quirk 12, D1)

- **Symptom:** the event handlers answered before the work was done; provider registration, status updates and
  publishes ran detached, their failures were logged (a payment could stay unregistered) or terminated the process.
- **Fix:** everything is awaited in the handler, callback or job; a failure propagates, so Dapr (events) or the
  simulation (callbacks) redeliver; the handlers are idempotent (P4, row lock). The database writes are short
  `PaymentService` transactions; the provider calls run outside them (like order's checkout and shipment's provider
  registration, no connection is held during a remote call): payment creation = store, register, publish
  `payment-enabled` (undone on failure, P1); a credit card retry = count the retry in the callback's transaction,
  then register again (a failing registration fails the callback, which the simulation repeats).
  `MISARCH_EXIT_ON_UNHANDLED_ERROR` is gone.
- **Tests:** `a failing provider registration fails the event and stores nothing, the redelivery succeeds`.
- **Load effect:** the event handler of `validation-succeeded` now includes the inserts, the provider round trip and
  the publish (the Dapr delivery takes longer, nothing runs after it).

## Not fixed (deliberately)

| Item | Reason |
|---|---|
| `payment-processed` never published, open orders never deleted (quirk 9) | no service subscribes to the topic; the original never wired `buildPaymentProcessedEvent` and the trigger (which status, which method) is not unambiguous. Publishing would add a Dapr publish per payment without any effect; the open-order table keeps growing as in the original |
| Invoice `FAILED` publishes nothing | explicit in the original ("There are no relevant events which need to be emitted for an invoice payment"); the order was already enabled |
| Callback for a payment whose information was deleted → 500 | rare (needs a delete while the payment is pending); the simulation retries 3× and gives up, as before |
| Credit-card regex quirks of validator.js (unanchored Mastercard alternative) | library behaviour, not a defect of the flow |
| Cron jobs per replica without leader election | deployment concern, single replica in compose |
