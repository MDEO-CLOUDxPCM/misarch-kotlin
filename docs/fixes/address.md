# Defect fixes: `address`

Baseline: tag `faithful` (the reference port; no separate spec, behaviour documented in the code and
`AddressIntegrationTest`). Tests: `tools/build.sh :services:address:test` → 12 green (10 integration, 2 SDL).
SDL, app id, topics, routes, `/dapr/subscribe` and the event payloads unchanged.

## Fixed

### 1. One unknown id failed the whole data-loader batch
- **Symptom:** `AddressDataLoader` / `UserDataLoader` used `entities[it]!!`: one unknown id in `address(id)` or in a
  gateway `_entities(UserAddress|VendorAddress)` call (e.g. addresses referenced by orders and shipments) failed every
  key of the batch with a NullPointerException.
- **Fix:** both loaders opt in to `failMissingKeysIndividually` (libs/common, commit e785536 of the order/saga agent):
  only the unknown key fails with `NoSuchElementException()` (text unchanged, `Exception while fetching data
  (/address) : null`), the other entities resolve.
- **Tests:** `an unknown address id fails only its own key (FIXED)`.

### 2. `user/user/created` handler not idempotent
- **Symptom:** a redelivered event failed on the primary key, answered 500 and was redelivered forever.
- **Fix:** `INSERT … ON CONFLICT (id) DO NOTHING`; a duplicate answers 200.
- **Tests:** `address of an unknown user cannot be created and a duplicate user event is acknowledged (FIXED)`.

## Payload / contract changes
None.

## Not fixed (deliberately)
- **Mutations publish inside the transaction, before commit**: a publish failure rolls the write back (correct); an
  event can reach consumers just before the commit is visible, which no consumer observes (none calls back).
- **`archiveUserAddress` on an already archived address re-archives it** (new `archivedAt`, second
  `address/user-address/archived` event): idempotent for consumers (order marks the address archived again).
- **`_entities(User)` stub without existence check / NPE for a representation without `id`**: unreachable through the
  gateway (it always sends the key).
