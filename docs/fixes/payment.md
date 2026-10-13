# payment — defect fixes

The faithful port (commit `060498d`, spec `docs/specs/payment.md`) reproduced the defects of the original NestJS
service. This document lists the defects fixed since then, and those deliberately left as they are. The federation
SDL, Dapr app-id, topics, routes, `/dapr/subscribe` and the provider callback path are unchanged
(`PaymentSdlTest` / `tools/sdl-diff.sh payment` empty).

Tests: 141 before, 144 after (`tools/build.sh :services:payment:test`, incl. the SDL test and the 125-case
validation corpus).

## Fixed

### P1 — payment start failure crashed the service

- **Symptom:** an `order-validation-succeeded` event with an unknown `paymentInformationId` (or a non-numeric
  `compensatableOrderAmount`, or a database error) published `payment-failed` and then terminated the process
  (spec D1): the clean-up `openOrders.delete(order.id)` looks up `paymentId == order.id`, never matches and rejects.
  Every start failure restarted payment (lost in-flight work, restart time under load). A failure after the payment
  insert left an orphan OPEN payment.
- **Root cause:** clean-up by the wrong key, not awaited; payment and open order created in two steps.
- **Fix:** payment and open order are created in one transaction (`PaymentService.create`); on any failure nothing
  is stored, `payment-failed {order}` is published, the process keeps running.
- **Tests:** `a payment start failure publishes payment-failed, stores nothing and does not terminate` (unknown
  payment information and invalid amount; terminator not invoked; no payment row, no `payment-enabled`).
- **Contract:** none (`payment-failed` payload unchanged).

### P2 — failed credit-card payments hung forever (spec quirks 4 + 7)

- **Symptom:** the simulation answers 5 % of payments with `FAILED`. For a credit card the original "retried" with
  `POST ${SIMULATION_URL}/register` `{paymentId, type}` (no such route, 404), never stored `numberOfRetries`, so the
  limit of 3 never triggered: the payment stayed PENDING forever, the provider never processed the retry, no
  `payment-failed`. GraphQL always showed `numberOfRetries: 0`.
- **Root cause:** wrong endpoint/body in `CreditCardService.update`, counter never written.
- **Fix:** `numberOfRetries` is stored (0 at creation, migration `V2__fixes.sql` sets old NULLs to 0). A FAILED
  callback with fewer than 3 retries increments it atomically (`UPDATE … WHERE numberOfRetries < 3`) and registers
  the payment again with `POST ${SIMULATION_URL}/payment/register` `{"paymentId","amount","paymentType":"credit-card"}`
  (`amount` = stored `totalAmount`); answer: empty 201, status stays PENDING. The 4th FAILED sets FAILED (only on the
  transition) and publishes `payment-failed {order}`; answer: the payment JSON. A SUCCEEDED callback after a retry is
  stored as usual.
- **Load effect:** ~5 % of credit-card payments now cause another provider round trip (each retry fails again with
  5 %), i.e. ~1.05 register calls/callbacks per credit-card payment; ~0.0006 % end FAILED with `payment-failed`
  (inventory then releases the items).
- **Tests:** `failed credit card payments are retried three times, then fail and publish payment-failed`,
  `a credit card payment that succeeds on a retry is stored as succeeded`.
- **Contract:** the retry request now goes to `payment/register` (the simulation's existing route).

### P3 — successful prepayments were never stored (spec quirk 8)

- **Symptom:** prepayment `SUCCEEDED` published `payment-enabled` but left the payment PENDING without `payedAt`;
  7 days later the cron set it FAILED and published `payment-failed`, so inventory released the already shipped
  items. Wrong state in GraphQL, phantom failures.
- **Root cause:** `PrepaymentService.update` returned without updating the status.
- **Fix:** SUCCEEDED is stored with `payedAt`; `payment-enabled` is published only when the status actually changes
  (a repeated callback publishes nothing). Answer: the payment JSON (was empty).
- **Tests:** `prepayment success is stored and enables the order once, the cron leaves it alone`.
- **Contract:** callback answer body for this case (the simulation ignores it).

### P4 — no idempotency for redelivered events (spec quirk 11)

- **Symptom:** every redelivery of `user/user/created` added two more payment informations; a redelivered
  `discount/order/validation-succeeded` created a second payment for the same order, registered it at the provider
  again and published a second `payment-enabled` (double shipment work downstream).
- **Fix:** `user-created`: one transaction with a per-user advisory lock (`pg_advisory_xact_lock`); a default method
  (PREPAYMENT, INVOICE) the user already has is not created again. `validation-succeeded`: the open order stores the
  order id in the new unique column `OpenOrderEntity.orderId`; it is inserted first in the creation transaction
  (`ON CONFLICT DO NOTHING`); a conflict means the order already has a payment and the event is ignored (no payment,
  no register, no events). Both are safe under concurrent delivery. Orders without an id are not deduplicated.
- **Tests:** `user created adds prepayment and invoice once, also when redelivered` (sequential and 4 concurrent
  deliveries), `a redelivered validation-succeeded event creates no second payment` (sequential, concurrent, and
  concurrent first deliveries).
- **Contract:** none. Migration `V2__fixes.sql` (new column + unique index).

### P5 — failed prepayments released nothing (found during the fix work)

- **Symptom:** prepayment `FAILED` from the provider (5 %) only set the status. The payment-failed path (inventory
  releases the reserved items) never ran: the 7-day cron only selects PENDING payments. The items stayed RESERVED
  forever.
- **Root cause:** `PrepaymentService.update` handles only SUCCEEDED specially; the documented `payment-failed`
  ("published when the payment fails finally") was only wired to the cron and the credit-card limit.
- **Fix:** FAILED is stored and, on the transition, publishes `payment-failed {order}`.
- **Tests:** `failed prepayment is stored and publishes payment-failed once, an unpaid one is failed by the cron`.
- **Contract:** additional `payment/payment/payment-failed` events (same payload). Invoice FAILED is unchanged (no
  event; the original says explicitly "no relevant events" for invoices, and the order is already enabled).

### P6 — fragments under connections (spec quirk 6)

- **Symptom:** an inline fragment under `PaymentConnection`/`PaymentInformationConnection` failed with
  `Cannot read properties of undefined (reading 'value')`; a fragment spread left `totalCount`/`nodes` uncomputed
  (`Cannot return null for non-nullable field …totalCount.`).
- **Fix:** `queryKeys` uses graphql-java's `selectionSet.immediateFields` (fragments resolved).
- **Tests:** in `payment information access, filters, ordering and fragments`.

### P7 — `user` filter never matched, buyers could not delete their own information (spec quirk 1)

- **Symptom:** `Query.paymentInformations(filter: {user})` always returned nothing (string compared with the stored
  `{id}` object); `deletePaymentInformation` compared `"[object Object]"` with the caller id, so buyers always got
  Unauthorized for their own information.
- **Fix:** the filter matches `userId`; the owner check compares the stored owner id.
- **Tests:** `payment information access, filters, ordering and fragments`,
  `buyers delete their own payment information, not foreign ones, employees any`.

### P8 — `orderBy` without defaults (spec quirk 5)

- **Symptom:** `orderBy: {field: ID}` → `Invalid sort value: { _id: undefined }`; `{}` → error; `{direction}` only →
  unsorted (natural order).
- **Fix:** `field` defaults to `ID`, `direction` to `ASC` (the only field; same defaults as the other services).
- **Tests:** unit `sort defaults to ID ascending`; integration (ordering with partial `orderBy`).

### P9 — `deletePayment` returned an unpopulated payment (spec quirk 10)

- **Symptom:** after a successful delete, selecting `paymentInformation { … }` failed and nulled `data`.
- **Fix:** the payment information is loaded before the delete and returned with the deleted payment.
- **Tests:** in `payments filters, entities and deletePayment`.

### P10 — card mask and leaked card data (spec quirk 2)

- **Symptom:** `"4111 1111 1111 1111"` stayed unmasked in `publicMethodDetails` (the regex masked only digits followed
  by 4 contiguous digits); the provider-callback answer contained `secretMethodDetails` (raw card number).
- **Fix:** every digit except the last four is masked (`"**** **** **** 1111"`, separators kept); the callback answer
  leaves `secretMethodDetails` out (still stored, as the original's design intends).
- **Tests:** unit `credit card mask, Luhn and expiration format`; integration (masking, response body of a
  credit-card callback).
- **Contract:** callback answer body (the simulation ignores it).

### P11 — expiration validator accepted garbage (spec quirk 3)

- **Symptom:** `"abc"`, `"12"`, `"05-30"`, `"12/30abc"`, … were valid expiration dates (comparisons with NaN are false).
- **Fix:** the documented format `MM/YY` or `MM/YYYY` is enforced (1–2 digit month, whitespace around the parts
  allowed), then the original range checks.
- **Tests:** unit table; `ValidationCorpusTest`: the 12 corpus cases that the original accepted (`+5/30`, `05/+30`,
  `05/30/99`, `05-30`, `abc`, `12`, `12/`, `/30`, empty, `12/30abc`, `12abc/30`, `1e1/30`) now expect
  `The expiration date is invalid or expired`; all other cases keep the original's result. The seeder's `12/30` is
  valid.

## Not fixed (deliberately)

| Item | Reason |
|---|---|
| `payment-processed` never published, open orders never deleted (quirk 9) | no service subscribes to the topic; the original never wired `buildPaymentProcessedEvent` and the trigger (which status, which method) is not unambiguous. Publishing would add a Dapr publish per payment without any effect; the open-order table keeps growing as in the original |
| Invoice `FAILED` publishes nothing | explicit in the original ("There are no relevant events which need to be emitted for an invoice payment"); the order was already enabled |
| Fire-and-forget processing, process termination on unexpected failures of detached work (quirk 12, D1 mechanism) | convention of the original; with P1 the always-failing trigger is gone, remaining triggers are real failures (e.g. database down); `MISARCH_EXIT_ON_UNHANDLED_ERROR=false` switches it off |
| Callback for a payment whose information was deleted → 500 | rare (needs a delete while the payment is pending); the simulation retries 3× and gives up, as before |
| Credit-card regex quirks of validator.js, error texts, `"}"` in the Unauthorized messages, Nest error formats | library behaviour / wording, not defects of the flow |
| Cron jobs per replica without leader election | deployment concern, single replica in compose |
