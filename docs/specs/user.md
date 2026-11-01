# Service spec: `user`

Original: `/home/devbox/workspace/misarch/user` (Kotlin 1.9.23, Spring Boot 3.2.4, graphql-kotlin 7.0.2,
R2DBC + infobip QueryDSL 9.0.2, Dapr Java SDK 1.11.0, JDK 17). Package `org.misarch.user`.
Reference SDL: `/home/devbox/workspace/misarch/schemas/user.graphql`; it matches the code.

The code is the truth. Where the docs (`docs/.../services/user.mdx`) disagree, this spec follows the code.

## 1. Role in the system

- Owns `User` (id, username, firstName/lastName, birthday, gender, dateJoined).
- Fed by Keycloak (custom image plus its own Dapr sidecar) through `user/user/create`. The Keycloak
  publisher is not in the clones. `testdata/create-gatling-user.sh` publishes the same payload by hand
  (`POST user-dapr:3500/v1.0/publish/pubsub/user/user/create`).
- Publishes `user/user/created`. This is the largest fan-out in MiSArch (9 subscribers: address, discount,
  notification, invoice, order, review, shoppingcart, wishlist, payment).
- On the workload's critical path: every Gatling buy iteration runs `currentUser { id }`. The gateway
  also starts `currentUser { addresses … }` and `currentUser { paymentInformations … }` here, then calls
  `_entities(User)` on address and payment.

## 2. Runtime contract (must stay identical)

| Item | Value |
|---|---|
| HTTP port | 8080 |
| GraphQL | `POST /graphql` (gateway calls `localhost:3500/v1.0/invoke/user/method/graphql`); `GET /sdl` is used by `scripts/generate-graphql-schema.sh` |
| Health | `GET /health` (actuator, `management.endpoints.web.base-path: /`); Docker healthcheck `wget -qO - http://localhost:8080/health` |
| Dapr app-id | `user` (app-port `${EXPERIMENT_CONFIG_SIDECAR_PORT:-8080}`; the experiment-config-sidecar proxies to 8080) |
| Dapr endpoints served by app | `GET /dapr/subscribe`, `GET /dapr/config`, `GET /healthz` (from `dapr-sdk-springboot`) |
| Pub/sub component | `pubsub` |
| Env vars | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, optional `SPRING_FLYWAY_ENABLED` (`false` in the schema script), `OTEL_*` (read by the OpenTelemetry Java agent: `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_METRIC_EXPORT_INTERVAL`, `OTEL_LOGS_EXPORTER`, `OTEL_INSTRUMENTATION_HTTP_{CLIENT,SERVER}_EMIT_EXPERIMENTAL_TELEMETRY`, `OTEL_SERVICE_NAME=user`, `OTEL_TRACES_EXPORTER`), Dapr SDK defaults (`DAPR_HTTP_PORT`=3500, `DAPR_GRPC_PORT`=50001) |
| Image entrypoint | `java -javaagent:/home/java/opentelemetry-javaagent.jar -jar ./app.jar` |
| DB | Postgres 16, DB `user`, Flyway `classpath:db/migration`, `baselineOnMigrate=true` |
| Auth input | Header `Authorized-User: {"id":"<uuid>","roles":["buyer"|"employee"|"admin",…]}` set by the gateway |

## 3. GraphQL schema summary

- `Query.currentUser: User`: no auth. Returns null when there is no `Authorized-User` header.
- `Query.user(id: UUID!): User!`
- `Query.users(first: Int, skip: Int, orderBy: UserOrderInput): UserConnection!`
- `Mutation.updateUser(input: UpdateUserInput!): User!`
- `type User @key(fields:"id")`: `id`, `username`, `name: Name!`, `birthday: Date`, `gender: Gender`, `dateJoined: DateTime!`.
- `type Name @shareable {firstName, lastName}`, `UserConnection @shareable {hasNextPage, nodes, totalCount}`.
- `enum Gender {MALE, FEMALE, DIVERSE, OTHER}`, `enum UserOrderField {ID, USERNAME}`, `enum OrderDirection`.
- `input UpdateUserInput {id: UUID!, firstName: String, lastName: String, birthday: Date, gender: Gender}`.
- Federation 2.5 `@link` header. `_entities` union = `User`. All directives except federation's are cleared
  (`didBuildSchema … clearDirectives()`). Scalars: `DateTime` (OffsetDateTime, extended-scalars),
  `Date` (LocalDate), `UUID`.

## 4. Resolvers and authorization (exact semantics)

Shared auth helper (identical in all three services):
- `authorizedUser`: reads the context and throws `IllegalStateException("Unauthorized access")` when the header is missing.
- `isEmployee = roles ∋ employee || isAdmin`, `isAdmin = roles ∋ admin`.
- `checkIsEmployee()` throws `IllegalStateException("Unauthorized access: <id> is not an employee or admin")`.
- `checkIsAdmin()` throws `IllegalStateException("Unauthorized access: <id> is not an admin")`.
- A present but unparsable header (for example empty) makes context creation throw. The whole request
  then fails at HTTP level and no GraphQL error is returned.

| Operation | Behaviour |
|---|---|
| `user(id)` | Requires the header. If `id != caller.id`, the caller must be an employee. The user is then loaded through `UserDataLoader` (batch `findAllById`). An unknown id hits `entities[it]!!`, which throws NPE. Because the dataloader batches, **every key in the same batch fails**, not just the unknown one. **FIXED** (docs/fixes/user.md): only the unknown key fails (`NoSuchElementException()`, same text `… : null`). |
| `users(first, skip, orderBy)` | Requires employee (`dfe.authorizedUser.checkIsEmployee()`). Returns a `UserConnection` with no predicate and no filter. The default order is `ID ASC`. `USERNAME` orders by `(username, id)`, and the direction applies to both columns. |
| `currentUser` | Header missing: returns null. Header present: `findById(caller.id).awaitSingle()`. No row: NoSuchElementException, so the field returns an error and null. |
| `User.birthday` / `gender` / `dateJoined` | Field-level check. Requires the header, and the caller must be the same user or an employee. This applies to every path, including `_entities` and `users.nodes`. `dateJoined` is non-null, so a failure turns the enclosing `User` into null. `id`, `username` and `name` are not protected. |
| `updateUser(input)` | Requires the header. If `input.id != caller.id`, the caller must be an employee. Loads with `findById().awaitSingle()` (NoSuchElement if missing). `firstName` / `lastName`: null means unchanged. `birthday` / `gender` use `OptionalInput`: omitted means unchanged, explicit `null` clears the value. Then `save()`, a full-row `UPDATE`. **No event is published.** Returns the saved entity. |
| `_entities(User)` | `FederatedTypePromiseResolver`. If `representation.id` is null, the result is null. Otherwise the user is loaded through `UserDataLoader` (same NPE-on-missing behaviour; **FIXED**: an unknown id gives null + one error for its own entry only). |

Connection semantics (`BaseConnection`, identical in all three services). Each sub-field runs its own SQL query:
- `totalCount`: `SELECT count(id) FROM UserEntity [WHERE …]`, as Int.
- `nodes`: `SELECT * … [WHERE …] ORDER BY <order> OFFSET skip?:0 LIMIT first?:Long.MAX_VALUE`.
- `hasNextPage`: `false` when `first == null`. Otherwise `SELECT * … [WHERE …] OFFSET first+skip LIMIT 1` (no ORDER BY), and the result is whether a row exists.

## 5. Persistence

Flyway migrations, copied **byte-identical** (checksums must match the `flyway_schema_history` of existing DBs):
- `V1_1__create_relations.sql`: `uuid-ossp`; `UserEntity(id UUID PK DEFAULT uuid_generate_v4(), username, name, gender VARCHAR, birthday TIMESTAMPTZ, dateJoined TIMESTAMPTZ NOT NULL)`.
- `V1_2__change_to_first_last_name.sql`: renames `name`→`firstName` and adds `lastName VARCHAR NOT NULL`.

Identifiers are unquoted, so Postgres stores them lowercase (`userentity.firstname`). The original maps
them through infobip's `PascalCaseNamingStrategy` (table = class name, column = property name) and
QueryDSL `PostgreSQLTemplates.DEFAULT` (no quoting).

Column mapping: `gender` is the enum name (`MALE`…) as VARCHAR. `birthday` is a Kotlin `LocalDate` stored
in a **TIMESTAMPTZ** column (written as date; Postgres casts to midnight in the session time zone, which is
UTC in the postgres image; read back as `LocalDate`). `dateJoined` is `OffsetDateTime`.

Writes:
- Create: native `INSERT INTO UserEntity (id, firstName, lastName, username, dateJoined) VALUES (…)`.
  A native insert is needed because the id is preset, so `save()` would issue an UPDATE.
- Update: `save()`, which updates all columns.

Reads: `findById`, `findAllById` (dataloader), and the connection queries above.

## 6. Events

Subscribed (`@Topic(name="user/user/create", pubsubName="pubsub")`, route `POST /subscription/user/user/create`):
- Body: CloudEvent envelope; `data` = `{id: UUID, username: String, firstName: String, lastName: String}`.
  Unknown fields are ignored. A missing or null required field fails deserialization with HTTP 400.
- Handler:
  1. Inserts the row with `dateJoined = OffsetDateTime.now()`; gender and birthday are null.
  2. Reads it back with `findById().awaitSingle()`.
  3. Publishes `user/user/created`.
  4. Returns 200 with an empty body.
- Not transactional. A duplicate id raises a unique violation and returns HTTP 500, so Dapr redelivers
  and keeps failing. If the publish fails after the insert, the row stays. Every redelivery then fails
  on the duplicate key, so `user/user/created` is **never** published for that user.
  **FIXED** (docs/fixes/user.md): insert (`ON CONFLICT (id) DO NOTHING`) and publish run in one transaction; a failed
  publish rolls the insert back (the redelivery creates and publishes), an already processed id answers 200 without
  a second publish.

`/dapr/subscribe` (dapr-sdk-springboot 1.11, `DefaultObjectSerializer` with NON_NULL):
`[{"pubsubName":"pubsub","topic":"user/user/create","route":"/subscription/user/user/create","metadata":{}}]`

Published `user/user/created` (Dapr SDK `publishEvent("pubsub", topic, UserDTO)`; SDK 1.11 default protocol is gRPC, content type `application/json`):
```json
{"id":"<uuid>","username":"…","firstName":"…","lastName":"…","dateJoined":"2024-05-01T10:20:30.123456Z"}
```
- `dateJoined` is formatted with `DateTimeFormatter.ISO_OFFSET_DATE_TIME` from the value **read back from
  the DB**. That gives microsecond precision, trailing zeros stripped, and offset `Z` because r2dbc returns
  timestamptz at UTC.
- The docs' shape `{id, username, name: Name, dateJoined}` is wrong; the flat shape above is what is sent.
- Consumers rely on `id` (all), `firstName` and `lastName` (invoice), and `dateJoined` (Rust services).

No service invocation (neither outbound nor served beyond `/graphql`).

## 7. Quirks to preserve (record, don't fix)

1. Unknown id in `user` or `_entities` raises NPE and fails the whole dataloader batch. **FIXED** (per-key failure).
2. `currentUser` for an authenticated user without a DB row returns an error, not null.
3. Field-level authorization on `birthday`, `gender` and `dateJoined` also applies on federation paths.
   A non-employee who selects `dateJoined` of another user gets null for the whole User.
4. `updateUser` publishes no event, so downstream replicas never see name changes.
5. The create handler is non-transactional and not idempotent (see §6). **FIXED** (transactional, idempotent).
6. `birthday` is a `LocalDate` in a `TIMESTAMPTZ` column.
7. `hasNextPage` uses no ORDER BY. `nodes` without `first` uses `LIMIT 9223372036854775807`.
8. Error text: graphql-java `SimpleDataFetcherExceptionHandler`, `"Exception while fetching data (/path) : <message>"`.

## 8. Parts needing non-trivial migration

- **Persistence technology:** infobip QueryDSL with kapt-generated `Q*` classes has to move to whatever
  `libs/common` provides. The new mapping must keep unquoted PascalCase/camelCase identifiers. It must
  also keep the `LocalDate`↔`TIMESTAMPTZ` mapping for `birthday` and the same number of SQL round trips
  per GraphQL field.
- **Dapr:** `@Topic` and `DaprClient` (gRPC) have to move to the shared subscription/publisher
  abstraction. The `/dapr/subscribe` JSON, route path, payload JSON, and status codes (200 / 400 / 500)
  must stay the same.
- **`OptionalInput` semantics in `UpdateUserInput`:** omitted and explicit null must stay distinct
  (needs graphql-kotlin `OptionalInput` or an equivalent).
- **`dateJoined` event formatting:** must use the DB read-back value and microsecond precision.
- **Dataloader:** NPE-on-missing and batch-failure semantics.

## 9. Test plan

Unit:
- `AuthorizedUser`: `isEmployee` / `isAdmin`, and the exact exception messages.
- `UserService.updateUser`: null vs absent vs explicit-null semantics for each field.
- `UserEntity.toEventDTO`: ISO format including `Z` and stripped trailing zeros.

Integration (Testcontainers Postgres 16, original migrations):
- Flyway applies V1_1 and V1_2 with the original checksums. The app also starts against a DB that the
  original already migrated (same `flyway_schema_history`).
- `currentUser`: without the header returns null; with a header for an existing user returns the user;
  with an unknown user returns an error.
- `user(id)`:
  - self works; another user without employee role gives the `…is not an employee or admin` error;
    employee and admin work;
  - an unknown id gives an error;
  - a missing header gives `Unauthorized access`.
- `users`:
  - a non-employee gets an error;
  - `first`/`skip` paging, `totalCount`, `hasNextPage` (first null → false; boundary cases);
  - `orderBy` `ID` and `USERNAME` in both directions, with id as the tiebreaker.
- Field auth: `dateJoined` / `birthday` / `gender` of another user as a buyer gives an error and a null
  User; as an employee it is visible.
- `updateUser`:
  - partial update; explicit `birthday: null` clears it; omitted field is unchanged;
  - `gender` enum round trip; `birthday` round trip (LocalDate in timestamptz);
  - another user as a buyer gives an error.
- `_entities(representations:[{__typename:"User", id}])` resolves the user. Two ids where one is unknown
  makes both fail.

Events (fake Dapr HTTP endpoint):
- POST a CloudEvent to `/subscription/user/user/create`:
  - the row is created with `dateJoined ≈ now`;
  - exactly one publish of `user/user/created` to `pubsub` with the exact JSON keys and values;
  - HTTP 200.
- Duplicate delivery gives non-2xx and no second publish.
- Missing `username` gives 400.
- `GET /dapr/subscribe` equals the JSON in §6.

SDL: `/sdl` output diffed against `schemas/user.graphql` must be empty.

Smoke (image): `/health` is UP, and `/graphql` answers `{__typename}`.

## 10. Status

The service is done in `services/user`.
- `tools/sdl-diff.sh user` shows no difference, both against the live SDL and against `--schemas`.
- 15 tests are green: 12 integration tests (Testcontainers Postgres plus `FakeDaprSidecar`), 1 SDL test and 2 unit tests.
- The image `misarch-kotlin/user:dev` is built.

How each original part was mapped:
- **Persistence:** `UserRepository` is now a `CoroutineCrudRepository` and keeps the native `INSERT` verbatim.
  `UserConnection` is a `BaseConnection`, with `UserOrderField.USERNAME = ("username", "id")`.
- **Error messages:** `awaitSingle()` on an empty result is reproduced as
  `NoSuchElementException()` without message (`kotlinx.coroutines.reactor.Mono.awaitSingle`): the error reads
  `Exception while fetching data (/x) : null` (verified on the running original, docs/INTEGRATION.md F3). This applies to `currentUser` without a
  row, to `updateUser` with an unknown id, and to the read-back in the create handler.
- **Migrations:** they are copied byte for byte. The Flyway checksums are V1_1 = 25115277 and V1_2 = -1834563498,
  identical to those in the original databases.
- **Birthday:** the `LocalDate` stored in a `TIMESTAMPTZ` column round-trips. This is covered by tests.

## 11. Deviations

| Deviation | Reason | Expected effect |
|---|---|---|
| `user/user/created` is published via the Dapr HTTP API (`POST /v1.0/publish/pubsub/user/user/created`) instead of the SDK's gRPC client | Project-wide decision (docs/VERSIONS.md) | Same CloudEvent and payload JSON (tested key order and values). Per-publish latency differs slightly (HTTP/1.1 vs gRPC to localhost) |
| JDK 25 / Spring Boot 4.1 / graphql-kotlin 11 / Jackson 3 runtime | Uniform current stack | Startup and steady-state performance differ from the JDK 17 / Boot 3.2 original. This is the point of the project |
| QueryDSL SQL is replaced by `BaseConnection` SQL. `hasNextPage` runs `SELECT 1 … LIMIT 1 OFFSET n` instead of selecting all columns. `nodes` without `first` has no `LIMIT` instead of `LIMIT 9223372036854775807` | QueryDSL/kapt dropped (docs/VERSIONS.md) | Same results, same number of DB round trips per field |
| `updateUser` runs in one transaction (`@Transactional` service method); the original's `Mutation` had none | one transaction rule for all services (read-modify-write) | One BEGIN/COMMIT more per `updateUser`; same result |
| `dateJoined` in the event uses the offset that r2dbc returns | Same mechanism as the original | It is `Z` in the images (UTC). On a JVM or DB session in another time zone the offset would differ, in the original as well |

No deviation in: SDL, app id, topics, routes, `/dapr/subscribe` (equal to `reference/dapr-subscribe/user.json`), `/health`, env vars, DB schema, authorization, error texts.

## Integration fixes (2026-10-13, docs/INTEGRATION.md §4.1)

Found by the whole-system differential run against the running original:
- F1: events are decoded with Jackson-2 semantics for primitives (`spring.jackson.deserialization.fail-on-null-for-primitives: false`): a missing/null primitive becomes 0/false instead of HTTP 400, like the original (Spring Boot 3.2).
- F2: all connections use `PagingValidation.QUERYDSL` (`first: 0` → `Limit must be greater than 0.`, negative → `Offset must not be negative.`), verified on the running original.
- F3: a missing row throws `NoSuchElementException()` without message → `Exception while fetching data (/x) : null`, verified on the running original (was `… : No value received via onNext for awaitSingle`).
