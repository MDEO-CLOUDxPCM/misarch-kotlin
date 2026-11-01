# Service spec: `notification`

Original: `/home/devbox/workspace/misarch/notification`. Stack: Kotlin 1.9.23, Spring Boot 3.2.4, graphql-kotlin 7.0.2,
R2DBC with infobip QueryDSL 9.0.2, Dapr Java SDK 1.11.0 (subscriptions only), JDK 17. Package `org.misarch.notification`.
Reference SDL: `/home/devbox/workspace/misarch/schemas/notification.graphql`. It matches the code.

## 1. Role in the system

- Owns `Notification` (title, body, dateSent, dateRead, userId).
- Keeps a replica `UserEntity(id)` that `user/user/created` fills.
- Subscribes to `notification/notification/create`, but **no service publishes it** (report 01, T35). In
  the original deployment, the only way to create a notification is the employee-only GraphQL mutation.
  Keep this as is.
- Publishes nothing. There is no `DaprClient` bean and no `EventPublisher`.
- Not on the Gatling workload path. Its only steady load is `user/user/created` during user creation.

## 2. Runtime contract (must stay identical)

| Item | Value |
|---|---|
| HTTP port | 8080 |
| GraphQL | `POST /graphql` (the gateway uses `localhost:3500/v1.0/invoke/notification/method/graphql`), `GET /sdl` |
| Health | `GET /health` (actuator, base-path `/`) |
| Dapr app-id | `notification` |
| Dapr endpoints served by the app | `GET /dapr/subscribe`, `GET /dapr/config`, `GET /healthz` |
| Pub/sub component | `pubsub` |
| Env vars | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, `SPRING_FLYWAY_ENABLED` (optional), `OTEL_*` (`OTEL_SERVICE_NAME=notification`) |
| DB | Postgres 16, DB `notification`, Flyway `classpath:db/migration`, `baselineOnMigrate=true` |
| Auth input | Header `Authorized-User` (JSON `{id, roles}`) |

## 3. GraphQL schema summary

- `Query.notification(id: UUID!): Notification!`
- `Mutation.createNotification(input: CreateNotificationInput!): Notification!` and `Mutation.updateNotification(input: UpdateNotificationInput!): Notification!`
- `type Notification @key(fields:"id")`: `id`, `title`, `body`, `dateSent: DateTime!`, `dateRead: DateTime`, `isRead: Boolean!` (computed as `dateRead != null`), `user: User!`.
- `type User @key(fields:"id")`: `id`, plus `notifications(first, skip, orderBy: NotificationOrderInput): NotificationConnection!`.
- `NotificationConnection @shareable`, `enum NotificationOrderField {ID}`, `input NotificationOrderInput {direction, field}`.
- `input CreateNotificationInput {title, body, userId: UUID!}`, `input UpdateNotificationInput {id: UUID!, isRead: Boolean!}`.
- `_entities` union: `Notification | User`. Scalars: `DateTime`, `UUID`.

## 4. Resolvers and authorization

The auth helper is the same as in user (see `user.md` §4).

| Operation | Behaviour |
|---|---|
| `notification(id)` | Calls `NotificationDataLoader.load(id)` **first**, then checks auth in `thenApply`: the header is required, and if `n.userId != caller.id` the caller must be an employee. An unknown id fails the dataloader batch with an NPE. Because the load runs first, an unknown id reports the NPE rather than the auth error. **FIXED** (docs/fixes/notification.md): an unknown id fails only its own key (`NoSuchElementException()`, same text `… : null`). |
| `createNotification(input)` | Requires an employee (checked first). Then `userRepository.existsById(userId)`. If false, it throws `IllegalArgumentException("User with id <id> does not exist")`. Then `save(NotificationEntity(title, body, dateSent=now, dateRead=null, userId))` with the id taken from the DB default. Returns the saved entity, with `dateSent` taken from memory. |
| `updateNotification(input)` | The header is required; `dfe.authorizedUser` is evaluated before the service runs. `findById(id).awaitSingle()` throws NoSuchElement if missing. If `n.userId != caller.id` it calls **`checkIsAdmin()`** (employees are *not* enough, unlike the other checks). `dateRead` changes only when `(dateRead != null) != isRead`: it becomes `now` or `null`. It then **always** calls `save()`, a full-row UPDATE, even when nothing changed. |
| `Notification.user` | Loads through `UserDataLoader` from the **local replica table** `UserEntity`. The FK guarantees the row exists. Returns `User(id)`. |
| `User.notifications(...)` | Field-level check: the header is required, and if `id != caller.id` the caller must be an employee. Returns a connection with predicate `userId = id`, default order `ID ASC`. |
| `_entities(Notification)` | Goes through `NotificationDataLoader`, **with no auth check** (any caller can read any notification through federation). A null id returns null. An unknown id gives an NPE for the whole batch (**FIXED**: null + one error for that entry only). |
| `_entities(User)` | Returns a stub `User(id)` with no existence check. A missing `id` makes `UUID.fromString(null)` throw an NPE. |

The original's `Mutation` class carries `@Transactional(propagation = REQUIRES_NEW)` with an R2DBC reactive
transaction manager. As a result, `existsById` plus `save` (create), and `findById` plus `save` (update),
each run in their own transaction. The event handler is **not** transactional. Here `updateNotification` is a
`@Transactional` service method; `createNotification` (one INSERT after a read-only check, no event) runs without a
transaction for the mutation and the event alike (§11).

Connection semantics: same as `user.md` §4 (three queries, `hasNextPage` without ORDER BY).

## 5. Persistence

`V1_1__create_relations.sql` (copied byte-identical):
- `UserEntity(id UUID PK)`.
- `NotificationEntity(id UUID PK DEFAULT uuid_generate_v4(), dateSent TIMESTAMPTZ NOT NULL, dateRead TIMESTAMPTZ, title VARCHAR(255) NOT NULL, body TEXT NOT NULL, userId UUID NOT NULL FK→UserEntity)`.

Identifiers are unquoted and lowercased by Postgres, using the PascalCase naming strategy.

Writes:
- `INSERT INTO UserEntity (id) VALUES (:id)`, a native query.
- Notification insert through `save()` with a null id, so the DB generates the id.
- Notification update through `save()`, updating all columns.

Reads:
- `existsById`, `findById`, and `findAllById` (dataloaders for Notification and User).
- Connection queries on `NotificationEntity` with `WHERE userId = ?`.

## 6. Events

Subscribed. All routes return 200 with an empty body on success. If the payload fails to deserialize, the response is 400; exceptions give 500, and Dapr redelivers.

| Topic | Route | `data` | Handler |
|---|---|---|---|
| `user/user/created` | `POST /subscription/user/user/created` | `{id: UUID}`. Other fields of the user payload (`username`, `firstName`, `lastName`, `dateJoined`) are ignored. | `INSERT INTO UserEntity (id)`. A duplicate gives 500, and Dapr keeps redelivering it. **FIXED**: `ON CONFLICT (id) DO NOTHING`, a duplicate answers 200. |
| `notification/notification/create` | `POST /subscription/notification/notification/create` | `{title: String, body: String, userId: UUID}`. The docs say `userId: boolean`, but that is a docs typo. | `createNotification` with no auth check and no transaction. An unknown user gives `IllegalArgumentException`, so 500. |

`/dapr/subscribe` should return two entries, each with `pubsubName: "pubsub"`, the topic, `route: "/subscription/<topic>"` and `metadata: {}`. The order is the order the SDK registers them in. Topic order is not semantically relevant to Dapr.

Publishes nothing. Makes no service invocations.

## 7. Quirks to preserve

1. No producer exists for `notification/notification/create`, so the feature has no source of data at runtime.
2. `updateNotification` on another user's notification needs **admin**. Employees are rejected with `…is not an admin`.
3. `_entities(Notification)` bypasses the ownership check that `Query.notification` enforces.
4. `Query.notification` loads before it authorizes, so an unknown id gives an NPE error, not an auth error.
   (Order kept; the batch-wide NPE is **FIXED**, see §4.)
5. The `user/user/created` handler is not idempotent. Redelivery fails with a duplicate key and returns 500. **FIXED**.
6. `updateNotification` always writes the row, even when the read state is unchanged.
7. `_entities(User)` never checks whether the user exists.
8. `User.notifications` applies field-level auth, so a non-employee asking for another user's notifications gets an error, not an empty list.

## 8. Parts needing non-trivial migration

- The QueryDSL connections and dataloaders have to move onto the shared persistence and connection helpers in `libs/common`.
- `@Transactional(REQUIRES_NEW)` on suspend mutation functions needs an equivalent reactive transaction boundary per mutation.
- Federation: the `Notification` resolver goes through the dataloader, and `User` is a stub resolver.
- Dapr `@Topic` subscriptions need to move to the shared subscription mechanism.

## 9. Test plan

Unit
- The `updateNotification` toggle matrix: read/unread crossed with isRead true/false.
- Admin vs employee vs owner checks, with the exact error messages.

Integration (Testcontainers Postgres)
- Flyway checksums match the original.
- `createNotification`:
  - succeeds for an employee and an admin;
  - a buyer gets the employee error;
  - an unknown userId gives the `does not exist` error;
  - `dateSent ≈ now`, `isRead = false`, `dateRead = null`.
- `updateNotification`:
  - the owner marks a notification read and then unread;
  - an employee on someone else's notification gets the admin error;
  - an admin succeeds;
  - an unknown id returns an error.
- `notification(id)`:
  - the owner and an employee get the notification;
  - another buyer gets an error;
  - an unknown id returns an error.
- `User.notifications` via `_entities(User)`:
  - paging, totalCount, hasNextPage, and ordering ASC/DESC;
  - auth: self, employee, and a foreign buyer (error).
- `Notification.user { id }` resolves.
- `_entities(Notification)` works without a header (the quirk).

Events, using a fake Dapr endpoint and posting CloudEvents:
- `user/user/created` inserts the replica row. A duplicate gives non-2xx.
- `notification/notification/create` creates a notification for a known user. An unknown user gives non-2xx and no row.
- A malformed payload gives 400.
- `GET /dapr/subscribe` contains exactly the two subscriptions.

SDL: the diff against `schemas/notification.graphql` must be empty.

## 10. Status

The service is complete in `services/notification`.
- `tools/sdl-diff.sh notification` finds no difference, both against the live SDL and with `--schemas`.
- 15 tests pass: 9 integration, 1 SDL, and 5 unit tests (the toggle matrix plus the admin/employee check).
- The image `misarch-kotlin/notification:dev` is built.
- The migration is copied byte for byte. Its Flyway checksum is -621256545, the same as in the original DBs.

How the original was mapped:
- Repositories are `CoroutineCrudRepository`, keeping the native `INSERT INTO UserEntity` unchanged.
- `NotificationConnection` is a `BaseConnection` with the predicate `userId = ?`.
- Transactions are on the service methods (Spring's coroutine-aware reactive transactions).
- `Query.notification` still loads the data before it authorizes.

## 11. Deviations

| Deviation | Reason | Effect |
|---|---|---|
| JDK 25 / Boot 4.1 / graphql-kotlin 11 runtime | Uniform stack | Performance only |
| `BaseConnection` SQL instead of QueryDSL. `hasNextPage` uses `SELECT 1`; there is no `LIMIT` when `first` is null | QueryDSL/kapt dropped | Same results and the same number of round trips |
| `createNotification` (mutation) runs without a transaction; `updateNotification` is a `@Transactional` service method | one transaction rule for all services (a transaction only where several statements must be atomic) | One BEGIN/COMMIT less per `createNotification`; same result (one INSERT, nothing published) |
| `_entities(User)` without `id` resolves to `null` instead of a NullPointerException | libs/common `ReferenceEntityResolver` | Only malformed representations |

Publishing: none (the service publishes nothing). There is no deviation in the SDL, topics, routes, `/dapr/subscribe` (compared as a set with `reference/dapr-subscribe/notification.json`), schema, authorization or error texts.

## Integration fixes (2026-10-13, docs/INTEGRATION.md §4.1)

Found by the whole-system differential run against the running original:
- F1: events are decoded with Jackson-2 semantics for primitives (`spring.jackson.deserialization.fail-on-null-for-primitives: false`): a missing/null primitive becomes 0/false instead of HTTP 400, like the original (Spring Boot 3.2).
- F2: all connections use `PagingValidation.POSITIVE_LIMIT` (`first: 0` → `Limit must be greater than 0.`, negative → `Offset must not be negative.`), verified on the running original.
- F3: a missing row throws `NoSuchElementException()` without message → `Exception while fetching data (/x) : null`, verified on the running original (was `… : No value received via onNext for awaitSingle`).
