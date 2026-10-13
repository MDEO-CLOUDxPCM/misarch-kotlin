# invoice: fixed defects

Baseline: tag `faithful` (docs/specs/invoice.md §8: "no invoice is ever created and no event is ever published").
Tests: 11 → 12 green (`InvoiceIntegrationTest` 11, `InvoiceSdlTest` 1). SDL and `/dapr/subscribe` unchanged.
New migration `V2__fixes.sql` (table `user_address`, unique index on `invoices.order_id`).

## Invoices were never created (D1-D6), and every order event was redelivered forever

- **Symptom:** every `discount/order/validation-succeeded` was answered 422 (D2) and redelivered by Dapr forever (one
  pending Redis entry per order: load); even a deserializable payload always ended in 500 (D3, D6). `Order.invoice`
  through the gateway always failed. `address/vendor-address/created` was 422 forever (D5), `user/user/created` 404
  (dropped, D1); the archive handler answered 500 for users with addresses.
- **Root causes:** D1 the subscription route `/on-id-creation-event` was not served and the handler expected
  snake_case names; D2 serde enum names (`Placed`) and the key `cVC` while discount publishes `PLACED` and `cvc`
  (and omits null fields); D3 address events were pushed to `user_addresses` of an existing user only, the lookup read
  `addresses`; D6 the lookup's projection dropped the names it then required; D4 the publish went to the non-existent
  component `invoice`; D5 snake_case vendor payload and an upsert that stored only the id.
- **Fix:**
  - order payload: Jackson enum names (serde names still accepted), `rejectionReason`/`vatNumber`/`paymentAuthorization`
    optional, `paymentAuthorization` `{"cvc": n}` (or `{"cVC": n}`);
  - `/on-id-creation-event` serves the user handler (camelCase `firstName`/`lastName`, snake_case still accepted),
    idempotent;
  - user addresses are stored in their own table `user_address` (independent of the user row and of event order,
    idempotent, keeps the addressee's name); archiving marks the row (orders placed with it still get their invoice);
  - vendor addresses: camelCase (or snake_case) payload, all fields stored (upsert); the invoice uses the first complete
    one (natural order, as the original);
  - `createInvoice`: invoice address from `user_address`, customer name from the user replica (fallback: the
    addressee's name), one invoice per order (a redelivery after success is acknowledged without a second invoice);
    the insert and the publish to `pubsub` / `invoice/invoice/created` run in one transaction - a failed publish (error
    answer or transport error) rolls the invoice back and answers 500 (Dapr redelivers). A missing replica (address,
    vendor address, user and addressee name) answers 500 so that Dapr retries once it has arrived.
- **Payload change:** `invoice/invoice/created` is now actually published (topic `invoice/invoice/created`, component
  `pubsub`; no subscriber in MiSArch). Its `order` uses the Jackson enum names (`PLACED`) and `{"cvc": n}` like the rest
  of the system (the original's serde forms `Placed`/`cVC` were never published anywhere).
- **Effect on load:** one invoice insert and one publish per order instead of an endlessly redelivered event.
- **Tests:** the real discount payload with replicas from the real user/address/vendor payloads → one invoice, the
  event, `Order.invoice` through `_entities`; redelivery → no second invoice or event; missing replicas → 500, then
  200 with the addressee's name; serde forms; failed publish → rollback and successful redelivery; vendor and user
  address events; publish target.
- **System run:** the replicas are fed by events from deployment on; an invoice database that existed before this fix
  has no users/addresses (they were dropped or stored where nothing reads them), so the run needs fresh volumes (or a
  re-seed: invoices are then created for the orders of the new users).

## Not fixed (deliberately)

- No authorization on invoice data (`invoice`, `_entities`): the original's API has none and the SDL exposes it.
- `{"status":0}` event answers, Rust type names in messages, the terms typo "according the the": conventions/wording.
- The invoice uses the first complete vendor address in natural order (not the latest): original semantics.
