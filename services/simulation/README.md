# simulation

Stub of MiSArch's external payment and shipment providers (original: TypeScript/NestJS with RabbitMQ). Same HTTP
paths on port 8080, the same RabbitMQ queues, the same env vars, and the same timing and success semantics; error
answers are Spring's. The behavioural spec and all deviations are in
[`docs/specs/simulation.md`](../../docs/specs/simulation.md) (§6.1).

## What it does

```
payment  POST /payment/register  {paymentId, amount, paymentType}  ─► payments-queue  ─┐
shipment POST /shipment/register {shipmentId, ...}                  ─► shipments-queue ─┤ consumer (rate limit / min)
                                                                                         ▼
          wait *_PROCESSING_TIME s (default 5) ─► random() < *_SUCCESS_RATE (default 0.95)
          ─► POST ${PAYMENT_URL}/payment/update-payment-status {paymentId, status: SUCCEEDED|FAILED}
          ─► POST ${SHIPMENT_URL}/shipment/{id}/status          {status: DELIVERED|FAILED}
             (RETRY_COUNT retries, default 3, 5 s apart)
```

Other endpoints: `POST /payment|shipment/update` (manual status, blocks the automatic one), `POST
/payment|shipment/findAll`, `GET /ecs/defined-variables`, `POST /ecs/variables` (experiment-config sidecar, served by
libs/common), `GET /health` → `{"status":"UP"}`. There is no GraphQL, no database and no Dapr endpoint.

## Configuration

| Env | Property | Meaning |
|---|---|---|
| `RABBITMQ_URL` | `simulation.rabbitmq.url` | required, e.g. `amqp://guest:guest@simulation-rabbitmq:5672`. HTTP only starts once connected (retries every 5 s) |
| `PAYMENT_URL`, `SHIPMENT_URL` | `simulation.connector.payment-url`, `shipment-url` | callback base URLs (`http://payment:8080`, `http://shipment:8080`) |
| `RETRY_COUNT` | `simulation.connector.retry-count` | callback retries (integer, default 3) |
| `OTEL_*`, `MISARCH_OTEL_AGENT`, `JAVA_OPTS` | | template defaults (see docs/PORTING.md §9) |

Rate limits, processing times and success rates are experiment-config variables (`PAYMENTS_PER_MINUTE`,
`SHIPMENTS_PER_MINUTE` = 1000000, `PAYMENT_PROCESSING_TIME`, `SHIPMENT_PROCESSING_TIME` = 5 s,
`PAYMENT_SUCCESS_RATE`, `SHIPMENT_SUCCESS_RATE` = 0.95). They are set at runtime via `POST /ecs/variables` (the ECS).
Env vars with these names replace the defaults at start-up (fix S4 in docs/fixes/simulation.md). The rate limit
resets at second 0 of every minute; held messages stay unacked until then and are redelivered after a reconnect.

## Code map (`org.misarch.simulation`)

| Package | Content |
|---|---|
| `payment`, `shipment` | `PaymentController` / `ShipmentController` (register, manual update, findAll), services, DTOs, registrations |
| `processing` | `EventProcessor` (start-up before HTTP, consume, rate limit, delays, outcome, minute reset), `SimulationState` (in-memory registrations, counters, one lock), `SimulationScope`, `QueueConsumer`, `FatalErrorHandler`, ... |
| `amqp` | `RabbitConsumer` (one channel, unlimited prefetch, manual ack, reconnect, fatal on broker loss), `RabbitPublisher` (`{"pattern", "data"}` messages) |
| `connector` | `ConnectorService` (callbacks + retries), `WebClientCallbackTransport` |
| `ecs` | `ExperimentConfigService` (current values), `ExperimentConfigVariables` (the six `EcsVariable` beans) |

## Build, test, image

```
tools/build.sh :services:simulation:test     # 52 tests: unit (virtual time), controllers, RabbitMQ Testcontainers integration
tools/image.sh simulation                    # misarch-kotlin/simulation:dev
```

The integration tests start one `rabbitmq:3-management` container per test JVM; Ryuk removes it afterwards.
