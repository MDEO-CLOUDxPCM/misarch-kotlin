# simulation

Kotlin port of MiSArch's `simulation`, the stub of the external payment and shipment providers. The original is
TypeScript/NestJS with RabbitMQ. This is a drop-in replacement: same HTTP API on port 8080, the same RabbitMQ queues
and message JSON, the same env vars, and the same timing and success semantics.
The full behavioural spec, quirks and deviations are in [`docs/specs/simulation.md`](../../docs/specs/simulation.md).

## What it does

```
payment  POST /payment/register  {paymentId, amount, paymentType}  ─► payments-queue  ─┐
shipment POST /shipment/register {shipmentId, ...}                  ─► shipments-queue ─┤ consumer (rate limit / min)
                                                                                         ▼
          wait *_PROCESSING_TIME s (default 5) ─► Math.random() < *_SUCCESS_RATE (default 0.95)
          ─► POST ${PAYMENT_URL}/payment/update-payment-status {paymentId, status: SUCCEEDED|FAILED}
          ─► POST ${SHIPMENT_URL}/shipment/{id}/status          {status: DELIVERED|FAILED}
             (RETRY_COUNT retries, default 3, 5 s apart)
```

Other endpoints: `POST /payment|shipment/update` (manual status, blocks the automatic one), `POST
/payment|shipment/findAll`, `GET /ecs/defined-variables`, `POST /ecs/variables` (experiment-config sidecar),
`GET /health` → `{"status":"OK"}`. There is no GraphQL, no database and no Dapr endpoint, as in the original.

## Configuration

| Env | Meaning |
|---|---|
| `RABBITMQ_URL` | required, e.g. `amqp://guest:guest@simulation-rabbitmq:5672`. HTTP only starts once connected (retries every 5 s) |
| `PAYMENT_URL`, `SHIPMENT_URL` | callback base URLs (`http://payment:8080`, `http://shipment:8080`) |
| `RETRY_COUNT` | callback retries (default 3) |
| `OTEL_*`, `MISARCH_OTEL_AGENT`, `JAVA_OPTS` | template defaults (see docs/PORTING.md §9) |

Rate limits, processing times and success rates are experiment-config variables (`PAYMENTS_PER_MINUTE`,
`SHIPMENTS_PER_MINUTE` = 1000000, `PAYMENT_PROCESSING_TIME`, `SHIPMENT_PROCESSING_TIME` = 5 s,
`PAYMENT_SUCCESS_RATE`, `SHIPMENT_SUCCESS_RATE` = 0.95). They are set at runtime via `POST /ecs/variables` (the ECS).
Env vars with these names replace the defaults at start-up (the original ignored them; fix S4 in
docs/fixes/simulation.md). The rate limit resets at second 0 of every minute; held
messages stay unacked until then and are redelivered after a reconnect.

## Code map (`org.misarch.simulation`)

| Package | Content |
|---|---|
| `SimulationApplication.kt` | wiring, start-up ordering (broker before HTTP), minute cron |
| `processing` | `EventProcessor` (consume, rate limit, timers, outcome), `SimulationState` (in-memory repositories, counters, one lock), contracts (`QueueConsumer`, `FatalErrorHandler`, ...) |
| `amqp` | `RabbitConsumer` (one channel, unlimited prefetch, manual ack, reconnect, crash on broker loss), `RabbitPublisher` (NestJS `ClientRMQ` packets) |
| `connector` | `ConnectorService` (callbacks + retries), `WebClientCallbackTransport` |
| `provider` | `ProviderService` (register, manual update, findAll) |
| `web` | `SimulationRoutes` (NestJS/Express-compatible HTTP), `Validation` (class-validator rules) |
| `config` | `SimulationVariables` (ECS variables with JS coercion) |
| `js` | JavaScript semantics: `parseInt`, `Number`, `String`, truthiness, `JSON.stringify` |

## Build, test, image

```
tools/build.sh :services:simulation:test     # 56 tests: unit (virtual time) + RabbitMQ Testcontainers integration
tools/image.sh simulation                    # misarch-kotlin/simulation:dev
```

The integration tests start one `rabbitmq:3-management` container per test JVM; Ryuk removes it afterwards.
