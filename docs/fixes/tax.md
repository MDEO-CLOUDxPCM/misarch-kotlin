# Defect fixes: `tax`

Baseline: tag `faithful` (behaviour of the original, docs/specs/tax.md §8). Tests: 11 → 13 green
(`TaxIntegrationTest` 12, `TaxSdlTest` 1). SDL, topics and event payloads unchanged; `/dapr/subscribe` still `[]`.
Tax is a leaf service with light load (employees only, `_entities` lookups by catalog/order through the gateway).

## Q1 No transactions: a half-created tax rate breaks every read touching it (quirks 1 and 4)

- **Symptom:** `createTaxRate` ran three autocommitted statements (insert tax rate with `currentVersionId = NULL`,
  insert version, update tax rate) and then published. A failure after the first insert (DB error, connection loss)
  left a `TaxRate` without current version; every later read of it - including the whole `taxRates` page and any
  data loader batch containing it - failed with an NPE in `toDTO`. A failing publish left the DB changed and the event
  lost (catalog/order never learned about the rate).
- **Root cause:** the original's `Mutation` had no `@Transactional` (unlike the other Kotlin services).
- **Fix:** the service methods the mutations call are `@Transactional`, like in the other Kotlin services: all DB work
  of a mutation commits or rolls back together. The events are still published after the DB work, now inside the
  transaction: a failing publish fails the mutation and rolls it back (project-wide convention, PORTING.md §5).
  Residual edge case: `createTaxRate` publishes two events; if the first publish succeeds and the second fails, the
  first event is out while the DB rolls back (same as every multi-publish mutation in the system).
- **Tests:** `a failing publish rolls the whole mutation back, …` (was: `a failing publish does not roll back`):
  createTaxRate with a failing publish leaves no row and no tax rate without current version, `taxRates` still
  resolves; createTaxRateVersion with a failing publish leaves neither the version nor the `currentVersionId` change,
  the next version is again 2.

## Q2 Duplicate version numbers under concurrency (quirk 2)

- **Symptom:** two concurrent `createTaxRateVersion` calls for the same tax rate both read `MAX(version)` and
  inserted the same version number (no lock, no unique constraint).
- **Fix:** inside the mutation's transaction the tax rate row is locked first (`SELECT id FROM TaxRateEntity WHERE
  id = :id FOR UPDATE`, which also replaces the `existsById` check, same error message). Concurrent versions of one tax
  rate are serialized; different tax rates do not block each other. `createTaxRate` needs no lock (its row is not
  visible to others before commit). No schema change (a unique constraint would need a migration and would turn the
  race into an error instead of serializing it).
- **Tests:** `concurrent createTaxRateVersion calls get distinct version numbers` (8 parallel requests → versions 2..9).

## Q6 Unknown id fails the whole data loader batch (NPE)

- **Symptom:** `taxRate(id)`, `taxRateVersion(id)`, `_entities` of TaxRate/TaxRateVersion, `TaxRate.currentVersion`,
  `TaxRateVersion.taxRate`: one unknown id failed every key of the batch (e.g. a gateway `_entities` call with one
  stale id lost all tax rates of the product page).
- **Fix:** libs/common `BaseIdDataLoader` (every loader): only the
  unknown key fails with `NoSuchElementException()` (`Exception while fetching data (/x) : null`; in `_entities`
  graphql-kotlin's `Exception was thrown while trying to resolve federated type, representation=…`), the others resolve.
- **Tests:** `an unknown id fails only its own key of a data loader batch` (`_entities` with known and unknown
  TaxRate/TaxRateVersion; two aliased `taxRate` root fields).

## Not fixed (deliberately)

| Item | Reason |
|---|---|
| Quirk 3: `updateTaxRate` publishes nothing | There is no `tax/tax-rate/updated` topic and no consumer for it; catalog and order only use the id and the current version's rate, which `updateTaxRate` cannot change. Adding a topic is a contract change without a consumer. |
| Quirk 5: `currentVersion`, `TaxRateVersion.taxRate`, `_entities` need no authorization, root queries need employee | API convention of the original (the gateway resolves tax rates for buyers through `_entities`). |
| Quirk 7: mutation results carry the in-memory `createdAt` (ns), reads the DB value (µs) | Cosmetic, both are valid timestamps of the same instant (±1 µs). |
| No input validation (negative / >1 rates, empty names) | API convention of the original; the SDL documents no constraint. |
