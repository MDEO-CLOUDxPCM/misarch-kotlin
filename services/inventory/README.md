# inventory (Kotlin port)

Port of `misarch/inventory` (TypeScript/NestJS/MongoDB) to the uniform stack (Spring Boot 4, graphql-kotlin, R2DBC +
Flyway on Postgres). Behaviour, quirks and the test plan: [docs/specs/inventory.md](../../docs/specs/inventory.md).

Drop-in contract (unchanged): Dapr app-id `inventory`, port 8080, `POST /graphql` (federation SDL identical to
`reference/sdl/inventory.graphql`), `GET /health` → `{"status":"OK"}`, `GET /dapr/subscribe` byte-identical to the
original (7 subscriptions, routes `/product-variant-created`, `/order-created`, `/payment-enabled`,
`/payment-failed`, `/shipment-created`, `/shipment-status-updated`, `/discount-validation-failed`), publishes
`inventory/product-item/reservation-succeeded|failed` with `{"order": <order as received>[, failedProductVariantIds]}`.
order's synchronous `_entities(ProductVariant){inventoryCount}` call works without `Authorized-User`.

## Configuration

| Env var | Meaning |
|---|---|
| `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD` | Postgres (replace the original's `DATABASE_URI`/`DATABASE_NAME`) |
| `DAPR_HTTP_PORT` / `DAPR_HTTP_ENDPOINT` | Dapr sidecar (default `http://localhost:3500`, the original hard-codes `127.0.0.1:3500`) |
| `MISARCH_EXIT_ON_UNHANDLED_ERROR` | default `true`: a failing fire-and-forget task terminates the process like the original Node service; `false` only logs |
| `OTEL_*` | OpenTelemetry Java agent (image) |

## Layout

- `org.misarch.common.nest` (libs/common, `@Import(NestWebConfiguration::class)` on `InventoryApplication`) —
  emulation of the NestJS/JavaScript runtime semantics the original relies on: roles guard on the raw
  `Authorized-User` header, `@nestjs/apollo` error format, class-validator + `ValidationPipe` (messages and nested
  flattening), the original UUID scalar, fire-and-forget tasks, Nest REST error bodies, verbatim `/dapr/subscribe`
  and `/health` (shared with payment, docs/PORTING.md §12). `nest/` keeps only inventory's `queryKeys`.
- `graphql/` — schema types, Query/Mutation, federation entity resolvers.
- `event/` — Dapr subscription routes and the event DTO validation schemas.
- `persistence/` — SQL access (`ProductItemEntity`, `ProductVariantPartialEntity`), `service/` — business logic.

## What differs from the original

Reproduced on purpose (see spec §8): the filter-mapping bug (`nodes` empty whenever a filter field is set,
`totalCount` correct), `reserveProductItemBatch` returning `[]`, `limit(0)` reserving everything, phantom items
inserted by releases of orders without items, `deleteProductItem` non-null error, mixed-case subscription keys,
process termination on a duplicate `product-variant-created` (switchable), Mongo's WriteConflict on concurrent
reservations (same message).

Deviations (spec §8): Postgres instead of MongoDB (ids kept as case-sensitive `TEXT`, natural order via a `seq`
column, snapshot transactions as `REPEATABLE READ` — a conflicting writer waits for the other commit instead of
failing at once); no `extensions.stacktrace`; GraphQL parse/validation-phase errors (e.g. an invalid UUID literal)
use graphql-java's texts and HTTP 200 instead of Apollo's (HTTP 400); `data: null` is also written for
validation errors; JSON key order of responses; the empty-result filter quirk does not execute the original's
collection scan; JVM restart time after a reproduced crash.

## Tests

`tools/build.sh :services:inventory:test`: SDL comparison, validation corpus (162 inputs → exact messages, generated
from the original compiled DTOs, `src/test/resources/reference/`), NestJS/JS emulation unit tests, integration tests
(Testcontainers Postgres, fake Dapr sidecar) for every query, mutation, entity, route and published event.
