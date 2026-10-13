# payment (Kotlin port)

Port of `misarch/payment` (TypeScript/NestJS/MongoDB) to the uniform stack (Spring Boot 4, graphql-kotlin, R2DBC +
Flyway on Postgres). Behaviour, quirks and the test plan: [docs/specs/payment.md](../../docs/specs/payment.md).

Drop-in contract (unchanged): Dapr app-id `payment`, port 8080, `POST /graphql` (federation SDL identical to
`reference/sdl/payment.graphql`), `GET /health` → `{"status":"OK"}`, `GET /dapr/subscribe` byte-identical
(`/order-validation-succeeded`, `/user-created`), provider callback `POST /payment/update-payment-status`
(called by `simulation`), provider calls `POST ${SIMULATION_URL}/payment/register` (and the original's broken
credit-card retry `POST ${SIMULATION_URL}/register`), events `payment/payment/payment-enabled|payment-failed` with
`{"order": <order as received>}`, cron jobs every 15 minutes (overdue invoices 30 d, prepayments 7 d).

## Configuration

| Env var | Meaning |
|---|---|
| `SIMULATION_URL` | provider stub base URL (compose `http://simulation:8080`); unset → provider calls are skipped (as in the original) |
| `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD` | Postgres (replace `DATABASE_URI`/`DATABASE_NAME`) |
| `DAPR_HTTP_PORT` / `DAPR_HTTP_ENDPOINT` | Dapr sidecar (default `http://localhost:3500`) |
| `MISARCH_EXIT_ON_UNHANDLED_ERROR` | default `true`: a failing fire-and-forget task terminates the process like the original Node service (e.g. every payment start failure); `false` only logs |
| `PAYMENT_PROVIDER_URL` | set by infrastructure-docker, ignored (as in the original) |

## Layout

- `org.misarch.common.nest` (libs/common, shared with inventory, `@Import(NestWebConfiguration::class)` on
  `PaymentApplication`, docs/PORTING.md §12) — NestJS/JavaScript emulation (roles guard on the raw header,
  `@nestjs/apollo` error format, class-validator/`ValidationPipe`, UUID scalar, fire-and-forget tasks, Nest REST
  bodies, `Js`, `MongooseCast`).
- `nest/` — payment's own parts: scalars (`PaymentScalars.kt` + `PaymentScalarConfiguration`): `DateTime`
  (`new Date()` parsing, `toISOString()`), `JSONObject`, and a `Float` that writes integral numbers like
  `JSON.stringify` (`1999`, not `1999.0`); `queryKeys` (payment's fragment quirks).
- `graphql/` — schema types, Query/Mutation, entity resolvers, card validation (validator.js `isCreditCard`, the
  original expiration-date validator).
- `provider/` — payment process (start, provider callback, processors, cron jobs), provider HTTP connector, events.
- `event/` — Dapr routes + callback controller, DTO validation schemas; `persistence/`, `service/`.

## What differs from the original

Reproduced on purpose (spec §9): `user` filter never matching, buyers unable to delete their own payment
information, unmasked spaced card numbers, secret card data in the callback response, garbage expiration dates
accepted, `numberOfRetries` always 0, sort errors for partial `orderBy`, inline-fragment TypeError, credit-card
retry to the wrong path (payment stays PENDING), succeeded prepayments staying PENDING and failed by the cron after
7 days, `payment-processed` never published, open orders never deleted, `deletePayment` sub-selection errors,
no idempotency, fire-and-forget processing, process termination after a failed payment start (switchable).

Deviations: Postgres instead of MongoDB (text ids, `json` columns keep the received order/details verbatim);
no `extensions.stacktrace`; GraphQL parse/validation-phase errors with graphql-java texts and HTTP 200; `data: null`
also for validation errors; `DateTime` input supports the ECMAScript ISO formats (V8's legacy formats such as
`Jan 1 2024` become Invalid Date); provider calls with Spring WebClient (keep-alive, fewer default headers than
axios); on a payment start failure `payment-failed` is published before the process terminates (the original's
publish request is merely in flight); JVM restart time after a reproduced crash.

## Tests

`tools/build.sh :services:payment:test`: SDL comparison, validation corpus (125 inputs → exact messages, generated
from the original compiled DTOs with a fixed clock), unit tests (Date parsing, sort, number cast, card checks),
integration tests (Testcontainers Postgres, fake Dapr sidecar, fake simulation) for all queries, mutations, entities,
routes, the provider callback, published events, provider requests and the cron jobs.
