# simulation

The external payment and shipment providers of MiSArch: `payment` and `shipment` register payments and shipments
here; after a processing time each one succeeds or fails at random and the result is sent back to the service. The
registrations go through RabbitMQ queues (rate limit per minute). Original: `misarch/simulation` (TypeScript,
NestJS, RabbitMQ). Spec and deviations: [docs/specs/simulation.md](../../docs/specs/simulation.md), fixed defects:
[docs/fixes/simulation.md](../../docs/fixes/simulation.md).

```
payment  POST /payment/register  {paymentId, amount, paymentType}  ─► payments-queue  ─┐
shipment POST /shipment/register {shipmentId, ...}                  ─► shipments-queue ─┤ consumer (rate limit / min)
                                                                                         ▼
          wait *_PROCESSING_TIME s (default 5) ─► random() < *_SUCCESS_RATE (default 0.95)
          ─► POST ${PAYMENT_URL}/payment/update-payment-status {paymentId, status: SUCCEEDED|FAILED}
          ─► POST ${SHIPMENT_URL}/shipment/{id}/status          {status: DELIVERED|FAILED}
             (RETRY_COUNT retries, default 3, 5 s apart)
```

| Contract | Value |
|---|---|
| Dapr app id | none (called directly over HTTP; no Dapr endpoints, no GraphQL, no database) |
| HTTP | port 8080: `POST /payment/register`, `/payment/update` (manual status, blocks the automatic one), `/payment/findAll`, the same under `/shipment/`; `GET /health`; experiment config `GET /ecs/defined-variables`, `POST /ecs/variables` (`PAYMENTS_PER_MINUTE`, `SHIPMENTS_PER_MINUTE` = 1000000, `PAYMENT_PROCESSING_TIME`, `SHIPMENT_PROCESSING_TIME` = 5 s, `PAYMENT_SUCCESS_RATE`, `SHIPMENT_SUCCESS_RATE` = 0.95; env vars with these names replace the defaults) |
| queues | RabbitMQ `payments-queue`, `shipments-queue` (one consumer channel, unlimited prefetch, manual ack; the rate limit resets at second 0 of every minute, held messages stay unacked) |
| invokes | `POST ${PAYMENT_URL}/payment/update-payment-status`, `POST ${SHIPMENT_URL}/shipment/{id}/status` |
| env | `RABBITMQ_URL` (HTTP starts once connected), `PAYMENT_URL`, `SHIPMENT_URL` (all three required), `RETRY_COUNT` (callback retries, default 3), `OTEL_*`, start values of the experiment-config variables above |

## Layout

```
SimulationApplication.kt    Spring Boot entry point
controller/                 PaymentController, ShipmentController (register, manual update, findAll)
service/                    PaymentService, ShipmentService (in-memory registrations), ProcessingService (rate
                            limit, processing time, outcome), CallbackService (status callbacks with retries)
amqp/                       RabbitConsumer (start-up connect, minute reset and reconnect), RabbitPublisher,
                            FatalErrorHandler (exit code 1 on broker loss)
ecs/                        ExperimentConfigService (the six variables), ExperimentConfigVariables (EcsVariable beans)
config/                     configuration properties, callback WebClient and Random beans, SimulationScope
model/                      request/response DTOs, registrations, queue definitions
```

## Build and test

```
tools/build.sh :services:simulation:test     # unit (virtual time), controllers, RabbitMQ Testcontainers integration
tools/image.sh simulation                    # misarch-kotlin/simulation:dev
```

The integration tests start one `rabbitmq:3-management` container per test JVM.
