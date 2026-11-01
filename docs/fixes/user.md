# Defect fixes: `user`

Baseline: tag `faithful` (docs/specs/user.md). Tests: `tools/build.sh :services:user:test` → 15 green (12 integration,
1 SDL, 2 unit). SDL, app id, topics, routes, `/dapr/subscribe` and the `user/user/created` payload unchanged.

## Fixed

### 1. One unknown id failed the whole data-loader batch (spec quirk 1)
- **Symptom:** `UserDataLoader` used `entities[it]!!`; one unknown id in `user(id)` or in `_entities(User)` failed
  every key of the same batch with a NullPointerException, i.e. a gateway `_entities` call with one stale user
  reference returned no users at all.
- **Fix:** libs/common `BaseIdDataLoader` (every loader): only the unknown key fails, with `NoSuchElementException()` (error text unchanged:
  `Exception while fetching data (/user) : null`); `_entities` returns the known users and null + one error for the
  unknown one.
- **Tests:** `federation resolves users in batches, an unknown id fails only its own entry (FIXED)`; `user by id -
  authorization and unknown id` (unchanged text).

### 2. `user/user/create` handler: lost `user/user/created` events and endless redelivery (spec quirk 5, §6)
- **Symptom:** the handler inserted, read back and published without a transaction. If the publish failed, the row
  stayed, and every Dapr redelivery failed on the duplicate key (HTTP 500 forever), so `user/user/created` was
  **never** published: the 9 replicas (address, discount, notification, invoice, order, review, shoppingcart,
  wishlist, payment) never learned about the user and that user could not buy. Any harmless redelivery of an already
  processed event also looped forever.
- **Fix:** `@Transactional` handler: `INSERT … ON CONFLICT (id) DO NOTHING`, read back, publish only if the row was
  inserted. A failed publish rolls the insert back (the redelivery then creates and publishes); a redelivery of an
  already processed event answers 200 without a second insert or publish (the first delivery's data wins).
- **Tests:** `failing publish fails the event and rolls back, the redelivery creates and publishes (FIXED)`,
  `duplicate user create event is acknowledged without publishing again (FIXED), invalid payload is rejected`.

## Payload / contract changes
None.

## Not fixed (deliberately)
- **Quirk 2 `currentUser` errors for an authenticated user without a row**: an inconsistency worth surfacing (the
  Keycloak user exists but `user/user/create` was not processed); returning null would hide it.
- **Quirk 3 field-level authorization of `birthday`/`gender`/`dateJoined` also on federation paths**: intended privacy
  rule; the null `User` for a non-null `dateJoined` is the GraphQL consequence of the SDL.
- **Quirk 4 `updateUser` publishes no event**: there is no `user/user/updated` topic and no consumer; adding one would
  need new topics and consumers in other services (not a fix within this service).
- **Quirk 6 `birthday` as `LocalDate` in a `TIMESTAMPTZ` column**: round-trips correctly; original schema kept.
- **Quirk 7 `hasNextPage` without ORDER BY**: the existence of a row at an offset does not depend on the order.
- **Quirk 8 graphql-java error texts**: wording.
