# payment

Payment informations of users (prepayment, invoice, credit cards) and the payment of every validated order:
registration at the external payment provider (`simulation`), its status callbacks, and jobs that fail overdue
payments. Spring Boot 4, graphql-kotlin, R2DBC + Flyway on Postgres, like the other services. Originally
`misarch/payment` (TypeScript/NestJS/MongoDB); behaviour of the original, fixes and deviations:
[docs/specs/payment.md](../../docs/specs/payment.md), [docs/fixes/payment.md](../../docs/fixes/payment.md).

Contract: Dapr app-id `payment`, port 8080, `POST /graphql` (federation SDL identical to
`reference/sdl/payment.graphql`), `GET /health`. Subscribes to `discount/order/validation-succeeded` and
`user/user/created` (routes `/subscription/<topic>`); publishes `payment/payment/payment-enabled|payment-failed`
with `{"order": <order as received>}`. Provider: `POST ${SIMULATION_URL}/payment/register`
`{"paymentId","amount","paymentType"}` (also for credit-card retries), callback `POST /payment/update-payment-status`
`{"paymentId","status"}`. Jobs every 15 minutes (`0 */15 * * * *`): PENDING invoices older than 30 days and
prepayments older than 7 days fail (`payment-failed`).

Processing is awaited in the event handler, the callback and the jobs, each in one transaction: a failure (database,
provider, publish) rolls back and propagates, so Dapr or the simulation redeliver; the handlers are idempotent.

## Configuration

| Env var | Meaning |
|---|---|
| `SIMULATION_URL` | provider base URL (compose `http://simulation:8080`); unset → payments are not registered at a provider |
| `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD` | Postgres |
| `DAPR_HTTP_PORT` / `DAPR_HTTP_ENDPOINT` | Dapr sidecar (default `http://localhost:3500`) |
| `PAYMENT_PROVIDER_URL` | set by infrastructure-docker, not read |

## Layout

`persistence/` (entities `PaymentInformationEntity`, `PaymentEntity`, `OpenOrderEntity`; repositories), `service/`
(payments, payment informations, credit card validation), `provider/` (callback controller, provider client,
configuration properties), `graphql/` (Query, Mutation, types, connections, data loaders, entity resolvers, scalars),
`event/` (`EventController`, topics, event DTOs).

## Tests

`tools/build.sh :services:payment:test`: SDL comparison, credit card validation table, integration tests
(Testcontainers Postgres, fake Dapr sidecar, fake provider) for queries, mutations, entities, subscriptions, the
provider callback, published events, provider requests and the jobs, and a migration test for V3.
