# Port spec: `tax`

Original: `/home/devbox/workspace/misarch/tax` (Kotlin 1.9.23, Spring Boot 3.2.4, graphql-kotlin 7.0.2, graphql-java 21.4,
extended-scalars 21.0, R2DBC + infobip QueryDSL 9.0.2 (kapt), Flyway 9.x, Dapr Java SDK 1.11.0, JDK 17). Package
`org.misarch.tax`. Reference SDL: `/home/devbox/workspace/misarch/schemas/tax.graphql`; it matches the code.
The code is the truth.

## 1. Role in the system

- Owns `TaxRate(name, description, currentVersion)` and its immutable `TaxRateVersion(rate, version, createdAt)` history.
- Leaf service: it consumes **no** events and makes **no** synchronous calls. Nobody invokes it synchronously
  except the gateway (`http://localhost:3500/v1.0/invoke/tax/method/graphql`).
- Publishes `tax/tax-rate/created` (consumed by catalog) and `tax/tax-rate-version/created` (consumed by order,
  which keeps `tax_rates.current_version.rate`).
- Test data: one tax rate (19 %) created by `testdata`. Light load in the workload: employees only, plus `_entities` lookups.

## 2. Runtime contract (must stay identical)

| Item | Value |
|---|---|
| HTTP port | 8080 |
| GraphQL | `POST /graphql` (gateway), `GET /sdl`, `GET /graphiql` (graphql-kotlin defaults) |
| Health | `GET /health` (actuator, base-path `/`, only `health` exposed; 503 when r2dbc is DOWN); Docker healthcheck `wget -qO - http://localhost:8080/health` |
| Dapr app-id | `tax`, app-port `${EXPERIMENT_CONFIG_SIDECAR_PORT:-8080}`, dapr-http-port 3500 |
| Dapr endpoints served by app | `GET /dapr/subscribe` → `[]`, `GET /dapr/config`, `GET /healthz` (from `dapr-sdk-springboot`) |
| Pub/sub component | `pubsub`. Publishing goes through the Dapr SDK (gRPC by default). An HTTP publish with `application/json` is equivalent |
| ECS endpoints | **None.** `GET /ecs/defined-variables` returns 404. The sidecar catches the failed deserialization and treats it as "no variables". Experiment-config therefore never forwards `/ecs/variables` (that route would also be a 404) |
| Env vars | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, optional `SPRING_FLYWAY_ENABLED`, `OTEL_*` (`OTEL_SERVICE_NAME=tax`, …), Dapr SDK defaults |
| Image | `java -javaagent:/home/java/opentelemetry-javaagent.jar -jar ./app.jar` |
| DB | Postgres 16, DB `tax`, Flyway `classpath:db/migration`, `baselineOnMigrate(true)` (same custom Flyway bean as shipment) |
| Auth input | Header `Authorized-User` (gateway) |

## 3. GraphQL schema summary

- `Query.taxRate(id: UUID!): TaxRate!`, `Query.taxRateVersion(id: UUID!): TaxRateVersion!`,
  `Query.taxRates(first, skip, orderBy: TaxRateOrderInput): TaxRateConnection!`
- `Mutation.createTaxRate(input: CreateTaxRateInput!): TaxRate!`, `createTaxRateVersion(input: CreateTaxRateVersionInput!): TaxRateVersion!`,
  `updateTaxRate(input: UpdateTaxRateInput!): TaxRate!`
- `type TaxRate implements Node @key(fields:"id", resolvable:true) {id, name, description, currentVersion: TaxRateVersion!,
  versions(first, skip, orderBy: TaxRateVersionOrderInput): TaxRateVersionConnection!}`
- `type TaxRateVersion implements Node @key(...) {id, rate: Float!, version: Int!, createdAt: DateTime!, taxRate: TaxRate!}`
- Connections `TaxRateConnection`, `TaxRateVersionConnection` are `@shareable`.
- `enum TaxRateOrderField {ID, NAME}` (NAME = `name, id`), `enum TaxRateVersionOrderField {ID, VERSION, CREATED_AT}`
  (`version, id` / `createdAt, id`), `OrderDirection` (applies to every expression of the field).
- Inputs: `CreateTaxRateInput {name, description, initialVersion: TaxRateVersionInput!}`, `TaxRateVersionInput {rate: Float!}`,
  `CreateTaxRateVersionInput {taxRateId: UUID!, rate: Float!}`, `UpdateTaxRateInput {id: UUID!, name: String, description: String}`.
- Federation 2.5 `@link(import: ["@shareable","@key","@inaccessible","FieldSet"])`. `@inaccessible` is imported but
  **not declared or used** (graphql-kotlin output). `_entities` union = `TaxRate | TaxRateVersion`. The schema hook also maps
  `LocalDate` to `Date`, but no field uses it, so there is no `Date` scalar in the SDL.

## 4. Resolvers and authorization

Same auth helper as shipment and user (`"Unauthorized access"` and `"Unauthorized access: <id> is not an employee or admin"`).

| Operation | Behaviour |
|---|---|
| `taxRate(id)` | **Employee.** `TaxRateDataLoader`. An unknown id gives an NPE that fails the batch, so error and `data: null`. **FIXED (docs/fixes/tax.md):** an unknown id fails only its own key (`… : null`). |
| `taxRateVersion(id)` | **Employee.** `TaxRateVersionDataLoader`, same NPE behaviour. |
| `taxRates(...)` | **Employee.** Connection over all rates, default order `ID ASC`. |
| `TaxRate.currentVersion` | **No auth.** Data loader on `currentVersionId`. |
| `TaxRate.versions(...)` | **Employee.** `taxRateId = TaxRate.id`, default order `ID ASC`. |
| `TaxRateVersion.taxRate` | **No auth.** Data loader. |
| `_entities` TaxRate / TaxRateVersion | **No auth.** Data loaders. A null `id` resolves to null; an unknown id fails the batch. **FIXED:** only the unknown representation is `null` + error. |
| `createTaxRate(input)` | **Employee. No transaction** (**FIXED:** one `REQUIRES_NEW` transaction per mutation, publishes inside it). (1) INSERT TaxRate with `currentVersionId=NULL`. (2) `version = MAX(version WHERE taxRateId)+1 ?: 1` gives 1, and a TaxRateVersion is inserted (`createdAt = OffsetDateTime.now()`). (3) UPDATE TaxRate setting `currentVersionId`. (4) Publish `tax-rate/created`, then (5) `tax-rate-version/created`. Returns the TaxRate. |
| `createTaxRateVersion(input)` | **Employee. No transaction** (**FIXED:** transaction; `SELECT … FOR UPDATE` on the tax rate replaces `existsById`, same message). `existsById(taxRateId)`, or `IllegalArgumentException("TaxRate with id <id> does not exist.")`. Then INSERT the version (MAX+1), `findById` the rate, UPDATE its `currentVersionId`, publish `tax-rate-version/created`. Returns the version (in-memory `createdAt`). |
| `updateTaxRate(input)` | **Employee. No transaction.** `findById(id).awaitSingle()` (unknown gives `NoSuchElementException`). Only non-null `name`/`description` are applied, then a full-row UPDATE (even without changes). **No event published.** Fields cannot be cleared. |

No validation of the inputs: negative or >1 rates and empty names are accepted. Strings longer than 255 chars give a DB error.
Connections use the same `BaseConnection` semantics as shipment (§4 of shipment.md).

## 5. Persistence

`V1_1__create_relations.sql` (copy byte-identically):
- `TaxRateEntity(id UUID PK DEFAULT uuid_generate_v4(), name VARCHAR(255) NOT NULL, description VARCHAR(255) NOT NULL,
  currentVersionId UUID NULL)` with `fk_tax_rate_version` → `TaxRateVersionEntity(id)`.
- `TaxRateVersionEntity(id UUID PK DEFAULT uuid_generate_v4(), rate DOUBLE PRECISION NOT NULL, createdAt TIMESTAMPTZ NOT NULL,
  version INT NOT NULL, taxRateId UUID NULL FK → TaxRateEntity)`.
- Circular FKs. Unquoted camelCase identifiers fold to lowercase. Extension `uuid-ossp`.
- Repository query: `SELECT MAX(taxRateVersionEntity.version) FROM TaxRateVersionEntity taxRateVersionEntity WHERE taxRateVersionEntity.taxRateId = :taxRateId`
  (the Kotlin name is `findMaxVersionByProductVariantId`, a copy-paste name).

## 6. Events

Serialization: Dapr SDK `ObjectSerializer` (plain Jackson, `NON_NULL`). Neither payload has nullable fields.

| Topic | Payload | Notes |
|---|---|---|
| `tax/tax-rate/created` | `{id, name, description, currentVersionId}` | createTaxRate only |
| `tax/tax-rate-version/created` | `{id, rate: Double, version: Int, createdAt: String, taxRateId}` | createTaxRate (initial version, published **after** tax-rate/created) and createTaxRateVersion. `createdAt` = in-memory `OffsetDateTime.now()` formatted with `ISO_OFFSET_DATE_TIME` (JVM zone, so `Z` in containers; fraction as produced by the clock, trailing zeros dropped, e.g. `2026-10-13T12:00:01.123456Z`). It is **not** the DB-rounded value. |

Consumed: none (`/dapr/subscribe` → `[]`).

## 7. Experiment-config variables

None (the sidecar-owned variables only). The port must **not** add `/ecs/defined-variables` with entries. An empty
`{}` would be functionally equivalent for the sidecar, but a 404 is identical.

## 8. Quirks to preserve

1. No transactions. A failure between the steps of `createTaxRate` leaves a `TaxRate` with `currentVersionId = NULL`.
   Every later read that hits it, including the whole `taxRates` page and batches, then fails with an NPE in `toDTO`.
   **FIXED (docs/fixes/tax.md).**
2. Version numbering is `MAX+1` without locking or a unique constraint, so concurrent creates can duplicate a version number.
   **FIXED (docs/fixes/tax.md)** (row lock on the tax rate).
3. `updateTaxRate` publishes nothing, so catalog and order never learn about name or description changes. Not fixed: there is no topic for it (docs/fixes/tax.md).
4. Events are published after the DB writes are committed (autocommit). A publish failure leaves the DB changed and the event lost.
   **FIXED** with 1: the publish runs inside the transaction, a failure rolls the mutation back.
5. Reads of `TaxRate.currentVersion`, `TaxRateVersion.taxRate` and `_entities` are unauthenticated, while the root queries need employee.
6. The data loader NPE for unknown ids fails the whole batch. **FIXED (docs/fixes/tax.md).**
7. Mutation results carry the in-memory `createdAt` (ns, JVM zone). Reads carry the DB value (µs). Both are serialized by the `DateTime` scalar.

## 9. Parts needing non-trivial migration

- QueryDSL/infobip/kapt → template persistence (connections with multi-expression ordering, native MAX query).
- Dapr SDK publish → libs/common publisher with `NON_NULL` serialization. `/dapr/subscribe` must return `[]` without a `@Topic`.
- graphql-kotlin 7 → current: an identical SDL, including the odd `@link` import list containing `@inaccessible` without
  the directive definition.
- Flyway 9 → current (postgres module), checksum-compatible.
- `createdAt` string formatting (ISO_OFFSET_DATE_TIME of `now()`) and `DateTime` scalar output format.
- If libs/common provides an ECS controller by default, tax must keep returning 404 (or document `{}`).

## 9a. Observed on the original jar (oracle run 2026-10-13)

- Flyway checksum of V1_1: `855754994`. `/dapr/subscribe` → `[]`. `/ecs/defined-variables` → 404 with the Spring JSON error body.
- Raw `/sdl` differs textually from `schemas/tax.graphql` (CI post-processing). The raw output is the SDL oracle.
- All responses carry `extensions.ftv1` (federated tracing).
- Errors: `Unauthorized access`, `Unauthorized access: <id> is not an employee or admin`,
  `TaxRate with id <id> does not exist.`, unknown id → `… : null` (both `taxRate` and `updateTaxRate`).
  `TaxRate.versions` via `_entities` without a header gives `Unauthorized access` and `_entities: [null]`.
- Paging: `first:0` → `Limit must be greater than 0.`, negative offset → `Offset must not be negative.` (nodes and hasNextPage).
- An invalid `Authorized-User` header gives HTTP 400 with an empty body.
- Event examples: `{"id":…,"name":"vat","description":"standard","currentVersionId":…}`,
  `{"id":…,"rate":0.19,"version":1,"createdAt":"2026-10-13T12:06:08.311375917Z","taxRateId":…}`. `rate` 7 is printed as `7.0`.
  GraphQL `createdAt` → `2026-10-13T12:06:08.498Z`.

## 10. Test plan

Unit: version numbering (none gives 1, max+1), `updateTaxRate` partial update, auth helper messages, event DTO
mapping (`createdAt` format with a fixed clock).

Integration (Testcontainers Postgres 16):
- Flyway fresh, and a DB migrated by the original jar starts under the port (checksum).
- GraphQL: all three queries without a header, as buyer and as employee (exact error messages, `data: null`).
  `taxRates` paging, `orderBy NAME` / `ID` in both directions, `totalCount`, `hasNextPage`. `TaxRate.versions` ordering by VERSION
  and CREATED_AT, plus auth. `currentVersion` / `taxRate` without auth through `_entities`. Unknown ids give errors.
- Mutations: createTaxRate gives DB rows (circular FK filled), version 1 and the returned object. createTaxRateVersion gives version 2,
  updates currentVersion, and an unknown taxRate gives the exact message. updateTaxRate with only name / only description / unknown id.
- `/health`, and `/sdl` equal to `schemas/tax.graphql` (**empty diff**), `/dapr/subscribe` → `[]`, `/ecs/defined-variables` → 404.

Event tests (fake Dapr endpoint): createTaxRate publishes exactly two events in order with exact JSON (`createdAt` ISO
with offset, `rate` as a number). createTaxRateVersion publishes one. updateTaxRate publishes none. If the publish fails, the DB
rows still exist (no rollback).

Differential (optional): same scenario against the original jar and the port, then diff responses (ids and timestamps normalized), events and DB.

## 11. Port status (2026-10-13) and deviations

Status: **done**. `tools/sdl-diff.sh tax` is empty against `reference/sdl` and `--schemas`. `tools/build.sh :services:tax:test`:
11 green (10 integration, 1 SDL). Image `misarch-kotlin/tax:dev`.

Verification: a differential black-box run of the same scenario against the original jar and the port, each with a fresh
Postgres and a fake Dapr. All GraphQL responses (ids/timestamps normalized), error texts (`… : null` for unknown ids,
QueryDSL paging messages), the HTTP 400 for an invalid `Authorized-User` header, the event payloads (key order,
`rate: 7.0`, `createdAt` ISO with nanoseconds), the DB schema and the Flyway checksum are identical. The port also started
on the original-migrated DB.

Mapping notes: no `@Transactional` (like the original; since the fixes `Mutation` is `@Transactional(REQUIRES_NEW)`, docs/fixes/tax.md). Connections use `PagingValidation.QUERYDSL`. `ftv1` is not emitted (project decision, docs/PORTING.md §7; opt-in switch `misarch.graphql.legacy-federated-tracing` for experiments). Unknown ids throw `NoSuchElementException()` without a message, as `Mono.awaitSingle()` did.

Deviations: only the project-wide ones. Publishing goes over the Dapr HTTP API with unencoded topic slashes (the
original used the SDK over gRPC). The runtime is JDK 25 / Spring Boot 4.

## Integration fixes (2026-10-13, docs/INTEGRATION.md §4.1)

Found by the whole-system differential run against the running original:
- F1: events are decoded with Jackson-2 semantics for primitives (`spring.jackson.deserialization.fail-on-null-for-primitives: false`): a missing/null primitive becomes 0/false instead of HTTP 400, like the original (Spring Boot 3.2).

## Fixes (2026-10-13, docs/fixes/tax.md)

Defects of the original fixed in the port (state `faithful` = commit 060498d): one transaction per mutation
(quirks 1 and 4), row lock against duplicate version numbers (quirk 2), per-key data loader failures (quirk 6).
SDL, topics and payloads unchanged. `tools/build.sh :services:tax:test`: 13 green (12 integration, 1 SDL).
