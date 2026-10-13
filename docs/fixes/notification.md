# Defect fixes: `notification`

Baseline: tag `faithful` (docs/specs/notification.md). Tests: `tools/build.sh :services:notification:test` → 16 green
(10 integration, 1 SDL, 5 unit). SDL, app id, topics, routes and `/dapr/subscribe` unchanged.

## Fixed

### 1. One unknown id failed the whole data-loader batch (spec §4, quirk 4)
- **Symptom:** `NotificationDataLoader` and `UserDataLoader` used `entities[it]!!`: one unknown id in
  `notification(id)` or `_entities(Notification)` failed every key of the batch with a NullPointerException.
- **Fix:** both loaders opt in to `failMissingKeysIndividually` (libs/common, commit e785536 of the order/saga
  agent): only the unknown key fails with `NoSuchElementException()` (text unchanged, `… : null`). `notification(id)`
  still loads before it authorizes (an unknown id reports not-found, not an auth error).
- **Tests:** `federation - an unknown notification id fails only its own entry (FIXED)`; `notification by id - …
  unknown id is an error` (unchanged).

### 2. `user/user/created` handler not idempotent (quirk 5)
- **Symptom:** a redelivered event failed on the primary key, answered 500 and was redelivered forever.
- **Fix:** `INSERT … ON CONFLICT (id) DO NOTHING`; a duplicate answers 200.
- **Tests:** `user created event registers the replica, a duplicate is acknowledged (FIXED), invalid payload is rejected`.

## Payload / contract changes
None.

## Not fixed (deliberately)
- **Quirk 1 no producer for `notification/notification/create`**: no service publishes it; adding a producer would
  mean new behaviour in other services (out of scope). The subscription and handler stay as they are.
- **Quirk 2 `updateNotification` on another user's notification needs admin (not employee)**: a stricter rule than
  elsewhere, but a consistent, deliberate-looking authorization choice (marking someone else's notification as read);
  not clearly a bug.
- **Quirk 3 `_entities(Notification)` without ownership check**: unreachable for clients (the gateway does not expose
  `_entities`, and no other subgraph references `Notification`).
- **Quirk 6 `updateNotification` always writes the row**: harmless extra write, no wrong result.
- **Quirks 7, 8** (`_entities(User)` stub without existence check; field-level auth on `User.notifications`): intended.
- **`notification/notification/create` for an unknown user answers 500 (redelivered)**: unreachable (no producer).
