# Service spec: `simulation`

Original: `/home/devbox/workspace/misarch/simulation` (TypeScript, NestJS 10.3.4, amqplib 0.10.3,
`@nestjs/microservices` ClientRMQ, `@nestjs/schedule` 4.0.1 / cron 3.1.6, axios 1.6.8, class-validator 0.14.1 /
validator 13.11.0, Node 18). Commit `2e0d503` (2025-06-29), which is the commit the deployed image
`ghcr.io/misarch/simulation:main` was built from (image created 2025-06-29 13:17 UTC).

Since the defect fixes, the items marked "FIXED (Sn)" describe the original; the service behaves as described in
`docs/fixes/simulation.md`.

Since the unification (2026-11-01) the service no longer emulates NestJS/Express/JavaScript: plain Spring
`@RestController`s with typed DTOs, the ECS endpoints of libs/common, `@ConfigurationProperties`, Jackson. §2–§5
still describe the original; every resulting visible change is listed in §6.1 (D10–D29). The queue, timing and
callback semantics (§4) are unchanged.

The code is the reference. The docs page (`docs/dev-manuals/other-repositories/simulation.mdx`) says
"Database: MongoDB / Mongoose". That is wrong: there is no database dependency and no DB container. All state is
in process memory plus the RabbitMQ queues.

## 1. Role in the system

`simulation` is the stub for the external payment provider and the external shipment provider. It is not a load
generator and not a model. It sits on the asynchronous part of the buy process
(`01-misarch-architecture.md` §4.1 S24–S27, §5.1):

```
payment  --HTTP POST /payment/register-->  simulation --AMQP--> payments-queue  --consume-->
         (delay PAYMENT_PROCESSING_TIME, Bernoulli PAYMENT_SUCCESS_RATE)
simulation --HTTP POST ${PAYMENT_URL}/payment/update-payment-status {paymentId,status}--> payment

shipment --HTTP POST /shipment/register--> simulation --AMQP--> shipments-queue --consume-->
         (delay SHIPMENT_PROCESSING_TIME, Bernoulli SHIPMENT_SUCCESS_RATE)
simulation --HTTP POST ${SHIPMENT_URL}/shipment/{id}/status {status}--> shipment
```

Callers:
- payment (`payment/src/payment-provider-connection/connector.service.ts`) posts to `${SIMULATION_URL}/payment/register`
  with `{paymentId, amount, paymentType}`. `paymentType` is one of `prepayment`, `invoice`, `credit-card`. Payment only
  logs the result. The credit-card retry path posts `{paymentId, type}` to `${SIMULATION_URL}/register`. That route does
  not exist, so it gets a 404 that payment only logs. This is a payment defect, and the service keeps returning 404 there.
  (Since payment's fix P2 the retry is a normal `POST /payment/register`; `POST /register` stays a 404 route.)
- shipment (`ShipmentService.kt:182-220`) posts to `MISARCH_SHIPMENT_PROVIDER_ENDPOINT` (= `http://simulation:8080/shipment/register`)
  with `{shipmentId, ref, quantity, weight, address:{street1, street2, city, postalCode, country, companyName}}`. It
  retries `providerRetries` (3) times with a `providerRetryDelay` (500 ms) pause on any exception or non-2xx response,
  then makes one last attempt.
- the experiment-config sidecar (ECS) calls `GET /ecs/defined-variables` and `POST /ecs/variables` on `localhost:8080`.
  It also calls `GET /dapr/subscribe`, which must not return a JSON array of subscriptions. Today it returns 404, and the
  ECS then uses `[]`.

The simulation has no GraphQL subgraph, publishes no Dapr pub/sub events, has no Dapr subscriptions, and makes no Dapr
service invocations. Its Dapr sidecar (`simulation-dapr`, app-id `simulation`, app-port = ECS port 5000) exists only
so the ECS can receive `config/simulation` events.

## 2. Runtime contract (must stay identical)

| Item | Value |
|---|---|
| HTTP port | `8080` (hard-coded `app.listen(8080)`) |
| Dapr app-id | `simulation` (compose/k8s only; no Dapr code in the app) |
| Health | `GET /health` → `200 {"status":"OK"}` (now: actuator `{"status":"UP"}`, D20). Docker healthcheck: `curl -f -X GET http://localhost:8080/health`, so **the image must contain `curl`** |
| GraphQL / SDL | none |
| Pub/sub topics | none |
| RabbitMQ | queues `payments-queue`, `shipments-queue` (durable) on broker `RABBITMQ_URL` (compose: `amqp://guest:guest@simulation-rabbitmq:5672`, image `rabbitmq:3-management`) |
| Outbound HTTP | `POST ${PAYMENT_URL}/payment/update-payment-status`, `POST ${SHIPMENT_URL}/shipment/{id}/status` |
| Persistence | none (in-memory and the RabbitMQ queues) |

### 2.1 Environment variables

| Var | Used | Semantics |
|---|---|---|
| `RABBITMQ_URL` | yes, required | AMQP URL used by the consumer and both publishers. If unset, startup throws `RABBITMQ_URL is not set` and the process exits (exit code 1). Now: `misarch.simulation.rabbitmq-url` (`URI`); if unset, the binding fails and the start-up ends with exit code 1 (D25). |
| `PAYMENT_URL` | yes | Base URL for payment callbacks (compose: `http://payment:8080`). If unset, the value is `NOT_SET` and an error is logged. Callbacks then go to `NOT_SET/payment/update-payment-status`, which axios resolves against `http://localhost`, giving `http://localhost/NOT_SET/...` (port 80, refused, retries, give up). Now: required, `misarch.simulation.payment-url` (`URI`); if unset, the start-up fails (D25). |
| `SHIPMENT_URL` | yes | Same as `PAYMENT_URL`, for shipment (`http://shipment:8080`). Now: required, `misarch.simulation.shipment-url`. |
| `RETRY_COUNT` | yes, optional | Number of callback retries. Read on every callback as the raw env **string**; the default is the number `3`. The JS comparison `attempts <= RETRY_COUNT` coerces it, so a non-numeric value means NaN, which means no retries. Now: an `Int` (`misarch.simulation.retry-count`), read at start-up; a non-numeric value fails the start-up (D18). |
| `PAYMENTS_PER_MINUTE`, `SHIPMENTS_PER_MINUTE`, `PAYMENT_PROCESSING_TIME`, `SHIPMENT_PROCESSING_TIME`, `PAYMENT_SUCCESS_RATE`, `SHIPMENT_SUCCESS_RATE` | **ignored** | They are "defined variables". `ConfigurationService.onModuleInit` writes their defaults into the variable map, and lookups check that map before the environment, so an env value can never take effect (quirk Q1). k8s sets `PAYMENTS_PER_MINUTE`, `SHIPMENTS_PER_MINUTE` and `PROCESSING_TIME_SECONDS`, and none of them has any effect. FIXED (S4): a non-empty env value replaces the default at start-up; compose sets none of them. Now: bound to `ExperimentConfigProperties` (`misarch.simulation.experiment.*`, `Int?` / `Double?`); a non-numeric value fails the start-up (D19). |
| `NODE_ENV` | logging only | `production` gives rotating log files instead of console logs. |
| `OTEL_*`, `NODE_OPTIONS` | telemetry | Node auto-instrumentation exports HTTP metrics over OTLP/HTTP to `${OTEL_EXPORTER_OTLP_ENDPOINT}/v1/metrics` every 5 s (`otlp.js`), with `OTEL_SERVICE_NAME=simulation` (k8s mistakenly sets `payment`). Traces are off (`OTEL_TRACES_EXPORTER=none`). The service follows the uniform template's telemetry setup and honours the same `OTEL_*` vars. |

### 2.2 Experiment-config variables (`/ecs`)

`GET /ecs/defined-variables` returns **200** with exactly this object (key order as shown):

```json
{
  "PAYMENTS_PER_MINUTE":      {"type":{"$schema":"http://json-schema.org/draft-07/schema#","type":"integer"},"defaultValue":1000000},
  "SHIPMENTS_PER_MINUTE":     {"type":{"$schema":"http://json-schema.org/draft-07/schema#","type":"integer"},"defaultValue":1000000},
  "PAYMENT_PROCESSING_TIME":  {"type":{"$schema":"http://json-schema.org/draft-07/schema#","type":"integer"},"defaultValue":5},
  "SHIPMENT_PROCESSING_TIME": {"type":{"$schema":"http://json-schema.org/draft-07/schema#","type":"integer"},"defaultValue":5},
  "PAYMENT_SUCCESS_RATE":     {"type":{"$schema":"http://json-schema.org/draft-07/schema#","type":"number"},"defaultValue":0.95},
  "SHIPMENT_SUCCESS_RATE":    {"type":{"$schema":"http://json-schema.org/draft-07/schema#","type":"number"},"defaultValue":0.95}
}
```

`POST /ecs/variables` takes a body `{ "<NAME>": <any JSON>, ... }` with no validation. It returns **201** with an empty body.
Entries are applied **in body key order**. An unknown key throws `Variable X is not defined`, which gives
**500** `{"statusCode":500,"message":"Internal server error"}`. The keys before it stay applied (partial update).
Coercion is JavaScript coercion on the JSON value:

- `integer` → `parseInt(String(v), 10)`. Examples: `5` → 5, `"7"` → 7, `0.9` → 0, `"12abc"` → 12, `1e21` → `"1e+21"` → 1,
  `1e-7` → `"1e-7"` → 1, `null` / `true` / `{}` / `"abc"` → NaN, `[5]` → 5.
- `number` → `Number(v)`. Examples: `0.5` → 0.5, `"0.5"` → 0.5, `""` / `null` / `false` / `[]` → 0, `true` → 1,
  `"0x10"` → 16, `"abc"` / `{}` → NaN.
- (`boolean` → `v === 'true'` and `string` → `String(v)` exist in the code but no simulation variable uses them.)

A variable's value is read at the moment it is used, so changes apply immediately (see §4). The ECS always sends the
full variable set of the service. Values come from its JSON (`JsonElement`), so they are normally JSON numbers.

Now (D16, D17): the endpoints come from libs/common (`EcsVariable` beans, `ecs/ExperimentConfigVariables`); the
`defined-variables` answer is unchanged. The variables are libs/common `IntegerVariable`/`NumberVariable`s held by
`ExperimentConfigService`. `POST /ecs/variables` answers **200** (empty). A value must match the declared JSON schema:
`integer` variables take a JSON number without fractional part in the `Int` range (`5`, `5.0`, `-1`); `number`
variables take any JSON number. Anything else (`0.9` or `1e21` for an integer, strings such as `"7"`, `null`, booleans,
objects, arrays) and an unknown name fail the request with **500** (Spring error body); the entries before it stay
applied. A non-object body is 400.

## 3. HTTP API

All routes are Express/NestJS in the original. The service uses plain Spring WebFlux controllers (`PaymentController`,
`ShipmentController`): same paths, methods and success status codes and bodies; error answers are Spring's (D10–D15).
Behaviour of the original:

- Every `@Post` handler answers **201 Created**. A handler that returns `undefined` gives an empty body (`Content-Length: 0`).
- A route that does not match gives **404** `{"message":"Cannot <METHOD> <path>","error":"Not Found","statusCode":404}`.
  This applies to `POST /register` (the credit-card retry path), `GET /dapr/subscribe` and `GET /payment/register`.
- Validation is a global `ValidationPipe()` with no options: `forbidUnknownValues:false`, no whitelist, no transform.
  A failure gives **400** `{"message":[<constraint messages in property declaration order>],"error":"Bad Request","statusCode":400}`.
  Extra properties are allowed and **kept**: the value passed on is `classToPlain(plainToClass(dto, body))`, which is
  the body with the same keys in the same order, nested objects copied as-is.
- The body is parsed as JSON only for `Content-Type: application/json`. Any other or missing content type gives `{}`,
  which then fails validation (400). Malformed JSON gives **400** (Express default error page, not JSON). A JSON array
  body fails validation like `{}`.
- Constraint messages: `IsUUID` → `"<p> must be a UUID"` (validator 13.11 `all` pattern
  `^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}$`, case-insensitive, string only); `IsNumber` →
  `"<p> must be a number conforming to the specified constraints"` (JS `number` only, not NaN, not ±Infinity, so a numeric
  string fails); `IsString` → `"<p> must be a string"`.

| Method & path | Request body | Success | Errors |
|---|---|---|---|
| `POST /payment/register` | `{paymentId: UUID, amount: number, paymentType: string, ...extra}` | 201, empty | 400 validation |
| `POST /payment/update` | `{paymentId: UUID, status: string}` (any string, not checked against the enum) | 201, empty | 400; 404 `{"message":"Shipment not found","error":"Not Found","statusCode":404}` (sic, "Shipment" for payments; FIXED (S3): `Payment not found`) |
| `POST /payment/findAll` | ignored | 201, `[{"id":…, "paymentType":…, "blocked":bool}, …]` | – |
| `POST /shipment/register` | `{shipmentId: UUID, ...extra}` | 201, empty | 400 |
| `POST /shipment/update` | `{shipmentId: UUID, status: string}` | 201, empty | 400; 404 same body as above |
| `POST /shipment/findAll` | ignored | 201, `[{"id":…, "blocked":bool}, …]` | – |
| `GET /ecs/defined-variables` | – | 200, see §2.2 | – |
| `POST /ecs/variables` | object | 201, empty (now: 200, D16) | 500 for an unknown key |
| `GET /health` (and `HEAD`) | – | 200 `{"status":"OK"}` | – |

### 3.1 `register` (payment and shipment)

1. Validate the body, then put an in-memory repository entry: payment `{id: paymentId, paymentType, blocked:false}`,
   shipment `{id: shipmentId, blocked:false}`. The repository is a list, so duplicate ids are allowed.
2. Publish, fire-and-forget, to the queue: `{"pattern":"register-payment"|"register-shipment","data":<validated body>}`.
   NestJS `ClientProxy.emit` returns a hot observable that is not awaited. The publish happens after connecting, so it
   always lands after step 1, even though the payment code calls `emit` first.
3. Answer 201 at once. The response never waits for or reflects the publish outcome. A broker problem loses the message
   silently; amqp-connection-manager buffers publishes while it reconnects.

### 3.2 `update` (manual override)

1. Look up the first repository entry with that id. If there is none, answer 404 ("Shipment not found"; FIXED (S3): "Payment not found" for payments).
2. Set `blocked = true` on that first entry.
3. Fire-and-forget callback with the given `status` string (§4.4, including retries): payment
   `{paymentId, status}`, shipment `/shipment/{shipmentId}/status {status}`.
4. Answer 201.

Entries are deleted when their queue message is consumed (§4.2, step 6). With the default rate limit (10^6/min), a message
is consumed within milliseconds of `register`. A manual update therefore only finds its entry while the message is held
back by the rate limit, and in practice it almost always gets **404** (quirk Q6). `findAll` shows exactly the entries
that have not been consumed yet.

## 4. Queueing, timing and success semantics (the core)

### 4.1 AMQP topology

- **Publishers:** two NestJS `ClientRMQ` clients (`PAYMENT_SERVICE` → `payments-queue`, `SHIPMENT_SERVICE` → `shipments-queue`),
  each on its own amqp-connection-manager connection with a ConfirmChannel. Each asserts its queue `{durable:true}`
  and publishes with `sendToQueue(queue, JSON, {persistent:false, headers:{}})`, which is deliveryMode 1, transient, with no
  content-type. They connect lazily on the first `emit`. They also consume `amq.rabbitmq.reply-to` with noAck, which is unused.
- **Consumer:** one plain amqplib connection with **one channel shared by both queues**. It asserts both queues
  `{durable:true}` and calls `consume(queue, cb, {noAck:false})`. **No `prefetch`**, so prefetch is unlimited: the broker
  pushes every ready message to the consumer at once. The connection has no `error` or `close` listener.
- Messages that are being held (§4.3) stay *unacked* on the consumer channel, not *ready* in the queue.

### 4.2 Consuming one message (`consumeMessage`, atomic in Node's single thread)

For a delivery on queue `q`, where `P` = `PAYMENT`/`PAYMENTS` and `S` = `SHIPMENT`/`SHIPMENTS`:

1. `max = <P|S>S_PER_MINUTE`, `delay = <P|S>_PROCESSING_TIME`. Both are read now.
2. If `count[q] >= max`: set `processingAllowed[q] = false` and return **without ack** (the message is held). This check
   runs before the null check and before parsing.
3. If the message is null (consumer cancelled), return.
4. `JSON.parse(content)` and take `data`. If the content is unparseable, the throw escapes amqplib, the connection
   emits `error`, the process crashes, and the unacked message is redelivered after the restart: a poison-message
   crash loop (quirk Q9). FIXED (S1, S2): an unparseable message, or one whose `data` has no truthy id of the
   queue's kind (`paymentId` / `shipmentId`), is acked, logged and dropped (not counted, no callback).
5. **Ack** the message. It is acked before any processing.
6. Blocked check. On `payments-queue` look up `data.paymentId`, on `shipments-queue` look up `data.shipmentId`, using
   strict equality on the repository ids. If there is no entry, it is not blocked (this happens after a restart). If
   there is an entry, delete **all** entries with that id and use the `blocked` flag of the first one. If it is blocked,
   return; the message is dropped and **not counted**.
7. `count[q]++`.
8. Schedule `setTimeout(delay*1000 ms)`. Node clamps a NaN, < 1 or > 2³¹−1 ms delay to 1 ms (now: `delay(max(0,
   delay) * 1000 ms)`, D21). When the timer fires:
   `id = data.paymentId || data.shipmentId` (JS truthiness; quirk Q8). If `id` is falsy, the timer throws an
   uncaught error and the process crashes. Otherwise the outcome is drawn and the callback is sent (§4.4).
   FIXED (S2): `id` is the id of the queue's kind, checked in step 4.

Concurrency: there is **no worker limit**. Every consumed message has its own timer, so the processing time is pure
latency (an infinite-server delay), not service capacity. The only throughput limit is `*_PER_MINUTE`.

### 4.3 Rate limit window and minute-boundary reconnect

- `@Cron('0 * * * * *')` runs at second 0 of every wall-clock minute (container local time, UTC). For each queue: if
  `processingAllowed[q] == false && max(q) > 0`, mark that a reconnect is needed. Then, for every queue, set
  `count[q] = 0` and `processingAllowed[q] = true`.
- **Reconnect** (if needed): `channel.close()`, which puts the channel into "closing" synchronously in the same tick, so
  no further deliveries are processed on it. Then `connection.close()`, `connectToRabbitMQ()` (one attempt; it asserts
  both queues), and `consume()` again for both queues. Closing the channel makes the broker **requeue every unacked
  (held) message** of both queues in original order (`redelivered=true`). The new consumer receives them at once and
  the limit applies again in the new window.
- So the limit is a fixed window aligned to the wall-clock minute: at most `max` messages per queue start processing per
  minute. The excess waits until the next `:00`, then competes again in FIFO order. Blocked messages do not count.
- `max = 0`: every message is held, and no reconnect happens (`max > 0` is false). Held messages stay stuck on the
  channel until some later minute in which `max > 0` and the limit is hit again (quirk Q4).
- `max = NaN` (from a bad ECS value): `count >= NaN` is false, so there is no limit.
- If the reconnect attempt fails: `connectToRabbitMQ` logs and returns false, then `consume` on the closed channel
  rejects without a handler, which is an unhandled rejection, and Node 18 crashes the process.

### 4.4 Outcome and callback

- At timer expiry, `successRate = <P|S>_SUCCESS_RATE` is read and `successful = Math.random() < successRate`
  (NaN gives always false).
  - payment: `SUCCEEDED` or `FAILED`. `POST ${PAYMENT_URL}/payment/update-payment-status` with body
    `{"paymentId":<id>,"status":"SUCCEEDED"|"FAILED"}`.
  - shipment: `DELIVERED` or `FAILED`. `POST ${SHIPMENT_URL}/shipment/${id}/status` with body `{"status":"DELIVERED"|"FAILED"}`.
- `send()` is fire-and-forget (not awaited) and uses axios JSON with Node 18's default agent (no keep-alive, so a new TCP
  connection per request) and no request timeout. It follows up to 5 redirects. An attempt fails on a network error or
  a non-2xx status. On failure: `attempts++`. If `attempts <= RETRY_COUNT`, it sleeps **5 s** and tries again. Total
  attempts = `RETRY_COUNT + 1` (default 4, at t, t+5s+rtt, …). After the last failure it gives up silently, and the
  status update is lost.
- `RETRY_COUNT` and the URLs: the URLs are read once at construction, `RETRY_COUNT` is read on every call (now: all
  three at start-up; the environment of a running process does not change).

### 4.5 End-to-end latency model (default config)

`register → callback start ≈ t_publish + t_broker + PROCESSING_TIME (5 s)`. The AMQP hops add milliseconds. Add 5 s per
failed callback attempt. Under the rate limit, add the wait until the next wall-clock minute. Rejection probability is
`1 − SUCCESS_RATE` (0.05), drawn independently for each message.

### 4.6 Lifecycle

- Startup: `ConfigurationService` init writes the defaults. Then the event processor connects to RabbitMQ and **retries
  every 5 s forever**. Nest only calls `app.listen(8080)` after that, so the HTTP port (and `/health`) stays closed
  until the broker is reachable. The ECS waits for the TCP port.
- Messages already in the queues when the service starts (from before a restart) are consumed at once. Their repository
  entries are gone, so they are processed normally.
- Restart effects: pending timers and in-memory repositories are lost. A message that was acked but is still delaying
  never gets a callback (quirk Q7). Held (unacked) messages return to the queue. Messages are transient, so they survive
  a simulation restart but not a broker restart.
- A broker connection or channel error on the consumer (broker restart, heartbeat loss, a channel-level error) raises an
  `error` event that nothing handles, so the process **crashes** and Docker restarts it (`restart: unless-stopped`).
- Shutdown closes the consumer channel and connection.

## 5. Quirks and known defects (reproduced by the faithful version; FIXED ones: docs/fixes/simulation.md)

- **Q1** Env values for the 6 defined variables are ignored; only ECS updates change them. `PROCESSING_TIME_SECONDS` (k8s) is unused. FIXED (S4) (`PROCESSING_TIME_SECONDS` stays unused).
- **Q2** Every POST answers 201, including `findAll`, which is a POST.
- **Q3** `PROCESSING_TIME` has type `integer`. ECS values are truncated with `parseInt`, so sub-second times are impossible. `0` gives 1 ms.
- **Q4** `*_PER_MINUTE = 0` holds messages forever. Raising the limit later does not release them until the limit is hit again in some later minute.
- **Q5** Manual `update` on a payment answers "Shipment not found". FIXED (S3). `status` is any string and is forwarded unchanged.
- **Q6** The manual update only works while the message has not been consumed, so with the default rate it is effectively always 404.
- **Q7** Messages are acked before the 5 s delay. A crash or restart in that window loses the callback (payment stays `PENDING`, which for prepayment means `payment-enabled` is never published; the shipment stays `PENDING`).
- **Q8** `id = data.paymentId || data.shipmentId`. A shipment registration that carries an extra truthy `paymentId` field gets its callback under that id. FIXED (S2). Extra fields are kept in the queued message.
- **Q9** Poison messages (unparseable, or without any id) crash the process. An unparseable message is a crash loop because it is never acked. FIXED (S1): acked, logged, dropped.
- **Q10** The count check runs before the null and parse checks. A held message is not parsed and not logged as processed.
- **Q11** The success rate is read when the timer fires, the processing time when the message is consumed, and the rate limit at each consumption and at the cron reset.
- **Q12** Callback failures are only logged after `RETRY_COUNT+1` attempts. Payment's own handler can answer 4xx or 5xx (unknown id), which costs up to 3 × 5 s of retry sleeps.
- **Q13** The repository is a list: duplicate registrations create duplicate entries. Consuming the first message deletes them all, so the second message's lookup is "not found" and it is processed.
- **Q14** Docs claim MongoDB and "users can query all currently queued events". `findAll` only shows entries that have not been consumed yet.
- **Q15** `POST /ecs/variables` with an unknown key applies the earlier keys partially and then answers 500. The ECS's `EnsureSuccessStatusCode` then fails the ECS's own event handler.

## 6. Design decisions

- **RabbitMQ is kept** (decision). Reasons:
  1. Fidelity of externally visible behaviour: held messages survive a simulation restart; the minute-boundary requeue
     and redelivery work the same way; the queues and their contents can be inspected in the management UI.
  2. Drop-in replacement: the service uses the same `simulation-rabbitmq` container, `RABBITMQ_URL`, queue names, durable
     flags and message JSON. The original's queued messages and the service's are interchangeable, so the image can be
     swapped while the broker keeps running.
  3. Performance comparability: the calibration measures the `misarch-simulation-rabbitmq-1` container's CPU. An
     in-process queue would remove that component from the deployment.

  Client: Spring AMQP (`spring-boot-starter-amqp`): `RabbitTemplate` publishes, one `@RabbitListener` per queue
  (`RegistrationListener`, direct listener container, manual ack, prefetch 0 = unlimited) consumes, the broker URL is
  `spring.rabbitmq.addresses` (env `RABBITMQ_URL`), the two durable `Queue` beans are declared on connect.
  Connection loss is recovered by Spring (the containers reconnect, the broker requeues the unacked messages).
- **No database.** State stays in memory exactly as in the original; there is no Postgres.
- **State**: the registrations live in `RegistrationService<R>` (one bean per provider, `ConcurrentHashMap` by id; a
  manual update and the consumption of the message are atomic per id), the per-queue counts and pause flags in
  `ProcessingService` (atomics). At the minute reset the counts are reset first, then the listener container of each
  queue that held messages and may process again is stopped and started: its channel closes, the broker requeues the
  held messages and redelivers them to the new consumer.
- **Startup**: the listener containers start with the application and connect (retrying) on their own; while the
  broker is unreachable, actuator's RabbitMQ health contributor makes `GET /health` answer 503, so the compose
  healthcheck waits for the broker.
- **Timers**: the processing delay and the 5 s callback retries are coroutine `delay`s; the
  background work runs on the `SimulationScope` bean (cancelled on shutdown), so tests use virtual time. The
  processing delay is `max(0, PROCESSING_TIME)` seconds. The success draws come from a `kotlin.random.Random` bean.
- **Cron**: `RegistrationListener.resetRateLimit`, `@Scheduled(cron = "${misarch.simulation.reset-cron}")` with
  `0 * * * * *` in `application.yaml`, which uses the same second-0 semantics. `-` disables it (tests).
- **Publishing**: `register` publishes the registration in the request (`RabbitTemplate.send` to the default
  exchange, routing key = queue name, transient, `application/json`); a failed publish fails the request (500).
- **HTTP client**: the `callbackWebClient` bean, a WebClient on a Reactor Netty `HttpClient.newConnection()`, which
  opens a new TCP connection per request like Node 18's default agent. It has no connect timeout and no response
  timeout; the JSON body is written with the application's `JsonMapper`. The callback URL is the configured base URL
  plus the path (`UriComponentsBuilder`, the shipment id is percent-encoded).
- **HTTP server**: one generic `ProviderController<R, U>` (`POST /register` 201, `POST /update` 200,
  `GET /registrations`), mapped to `/payment` and `/shipment` by two subclasses, with typed DTOs
  (`RegisterPaymentDTO(paymentId: UUID, amount: Double, paymentType: String)`, `RegisterShipmentDTO(shipmentId: UUID)`,
  `Update*DTO(id, status)`) bound by Jackson; a manual update of an unknown registration throws
  `ResponseStatusException(NOT_FOUND)`. Further properties of a registration are collected with `@JsonAnySetter` and
  relayed in the queued message. `spring.jackson.deserialization.fail-on-null-for-primitives: true` (simulation only)
  makes a missing or `null` `amount` a 400 instead of 0.
- **Queue messages** are the registrations as received, written by Jackson, parsed with Jackson on consumption.
- **Configuration**: `@ConfigurationProperties`: `SimulationConfigurationProperties` (`misarch.simulation.payment-url`,
  `shipment-url`, `retry-count`) and `ExperimentConfigProperties` (`misarch.simulation.experiment.*`,
  start values of the six variables), mapped from the original env names in `application.yaml`.
- **libs/common**: `MisarchCommonAutoConfiguration`, the DB, GraphQL and Dapr auto-configurations are excluded in
  `application.yaml`; `EcsAutoConfiguration` (own auto-configuration) serves `/ecs/*`, the common defaults
  (`MisarchDefaultsEnvironmentPostProcessor`, applied regardless of the exclusion) put actuator's health at `/health`.
  There is still no `/dapr/subscribe` (404).

### 6.1 Deviations (not reproduced, with reason)

- **D1** Some Express and NestJS framework extras are not reproduced: automatic `OPTIONS` answers, `X-Powered-By`,
  ETag/304, urlencoded form bodies, non-UTF-8 charsets (415). No caller uses any of them.
- **D2** ClientRMQ's unused `amq.rabbitmq.reply-to` consumer and its ConfirmChannel are dropped: the service publishes on
  a plain channel. Publisher confirms were never awaited by the HTTP response, so nothing visible changes. The number
  of AMQP connections stays the same: 1 consumer, plus 1 publisher per queue, opened lazily on the first emit.
- **D3** amqp-connection-manager buffers publishes during a publisher reconnect. The service's publishers use the Java
  client's automatic recovery and drop (and log) a publish made while disconnected. In the original the consumer
  connection crashes the process at the same moment, so those buffered messages were lost as well.
- **D4** (superseded by D11) Malformed JSON gave 400 with an HTML page shaped like Express's default error page.
- **D5** Logging (Spring Boot console instead of winston) and telemetry (OTel Java agent of the template instead of the
  Node SDK; the same `OTEL_*` env vars) follow the uniform template.
- **D6** Callback redirects are not followed; axios follows up to 5. Payment and shipment never redirect.
- **D7** `SIGTERM`: the original image runs `node` as PID 1 without a signal handler, so `docker stop` waits 10 s and
  then kills it. The service's JVM shuts down on `SIGTERM`. This only affects operations.
- **D8** Image: `eclipse-temurin:25-jre` has `wget` but no `curl`; the base image (`tools/docker/base.Dockerfile`)
  installs `curl`, so the original compose healthcheck `curl -f -X GET http://localhost:8080/health` works as well
  as the Kotlin compose setup's `wget -qO - http://localhost:8080/health`.
- **D9** Spring Boot startup takes several seconds, Node about one second. That matters only for restart time after a
  crash, not for the steady state.

Changes of the unification (2026-11-01; no caller depends on them: payment and shipment only check for 2xx, the ECS
uses `EnsureSuccessStatusCode`, only the simulation reads its queues):

- **D10** Error answers are Spring's: 400/404/405/415/500 with Spring Boot's JSON error body (`timestamp`, `path`,
  `status`, `error`, `requestId`) instead of the Nest bodies (`{"message":[...],"error":"Bad Request","statusCode":400}`,
  `Cannot POST /register`, `{"statusCode":500,"message":"Internal server error"}`). The 404 of a manual update has the
  same Spring body (no `Payment not found` / `Shipment not found` text; fix S3 now only affects the log line).
- **D11** Request bodies: a non-JSON content type is **415** (was `{}` → 400), an empty or malformed body is 400 with a
  JSON body (was an HTML page for malformed JSON), the size limit is Spring's codec default (256 KB; was 100 kB → 413).
- **D12** Paths match exactly: no case-insensitive or trailing-slash matching (`/PAYMENT/Register/` is 404). A wrong
  method on an existing path is 405 (was 404, e.g. `GET /payment/register`).
- **D13** Validation by Jackson instead of class-validator: missing/`null`/wrongly typed `paymentId`/`shipmentId`
  (UUID), `amount` (number) or `paymentType`/`status` (string) is a 400 without constraint messages. Jackson's default
  scalar coercion is more lenient in two places: a number for a string field is accepted as its text
  (`"paymentType": 7` → `"7"`), and a numeric string for `amount` is accepted (`"5"` → 5.0); UUIDs are parsed by `java.util.UUID` (case-insensitive).
- **D14** Ids are UUIDs: a registration's id is stored and called back in canonical lowercase form (an uppercase
  `paymentId` was kept as sent). The callback of a queue message uses the id string of the message.
- **D15** Queued messages are written by Jackson: `amount` (a `Double`) is always written with a fraction (`10` →
  `10.0`), the further properties of a registration keep their values but not their key order (Jackson buffers them),
  numbers in them follow Jackson (`1.0` stays `1.0`, was `1`). A queue message whose id is not a non-empty string (e.g.
  a number) is dropped like one without id (fix S1; was processed if truthy).
- **D16** `POST /ecs/variables` answers 200 (common controller) instead of 201.
- **D17** ECS values must match the declared schema (§2.2, libs/common `IntegerVariable`/`NumberVariable`): strings
  (`"7"`, `"0.5"`), `null`, booleans, objects, arrays, integers with a fraction (`0.9`) and integers outside the `Int`
  range (`1e21`) are rejected with 500 (were `parseInt`/`Number` results such as 7, 0.5, NaN, 0, 1). No variable can
  become NaN any more, so the NaN cases of §4 (no limit, always FAILED, 1 ms delay) cannot occur. A non-object body
  is 400 (was applied as index keys → 500). The ECS sends the JSON numbers of the experiment configuration, so a valid
  configuration is applied as before.
- **D18** `RETRY_COUNT` is an `Int`: a non-numeric value fails the start-up (was NaN → no retries); a decimal value
  fails as well (was compared as a number, `1.5` → 2 retries).
- **D19** Env values of the six variables (fix S4) are converted by Spring: a non-numeric value fails the start-up
  (was ignored with a warning); an empty value keeps the default.
- **D20** `GET /health` is actuator's health endpoint: `200 {"status":"UP"}` (was `{"status":"OK"}`); the compose
  healthcheck only checks the status code.
- **D21** Processing delay `max(0, PROCESSING_TIME) * 1000` ms: 0 or a negative time is no delay (was 1 ms), times above
  2³¹−1 ms are real delays (Node clamped them to 1 ms).
- **D22** Log lines use Kotlin/SLF4J number formatting (e.g. `[1/1000000]`).
- **D23** (superseded by D33) `findAll` answers were written by Jackson from typed DTOs.
- **D24** Unknown routes (e.g. `POST /register`, `GET /dapr/subscribe`, `GET /`) are still 404, with Spring's body.
- **D25** `RABBITMQ_URL`, `PAYMENT_URL` and `SHIPMENT_URL` are required: without one the start-up fails with Spring's
  binding error (was `RABBITMQ_URL is not set`; a missing callback URL was logged and its callbacks went to
  `http://localhost/NOT_SET/...` until they gave up). The compose setup sets all three.
- **D26** A second registration of an id keeps the first one (paymentType, blocked flag) instead of adding a duplicate
  entry: `findAll` lists each id once, in no particular order (was the registration order, duplicates included). The
  processing is the same as with the duplicate list (Q13): the first consumed message ends the registration, a later
  message of the same id is processed as unknown.
- **D27** (superseded by D30) A fatal error closed the application context before the exit with code 1.
- **D28** The counts and pause flags are atomics instead of a lock around every consumption: a message consumed while
  the minute window resets counts toward either window, and the reconnect follows the reset instead of being part of
  it (deliveries in between are processed in the new window and acked, not requeued).
- **D29** Callback URLs are built with `UriComponentsBuilder`: a shipment id that is not URL-safe is percent-encoded
  (UUIDs are unchanged).

Changes of the second unification round (2026-11-01; nothing outside the simulation reads the queues or calls these
routes: payment and shipment only call `register` and check for 2xx):

- **D30** Spring AMQP instead of the plain client: a lost broker connection is recovered (consumers and publishing
  reconnect, the broker requeues the unacknowledged messages) instead of ending the process with exit code 1
  (`FatalErrorHandler` and D27 are gone); an exception while consuming is logged by the container. The HTTP port opens
  without waiting for the broker; `/health` is 503 until RabbitMQ is reachable (was: port closed).
- **D31** Queue messages are the registration JSON itself (`{"paymentId":...,"amount":...,"paymentType":...}`), without
  the `{"pattern": ..., "data": ...}` envelope, with content type `application/json` (still transient). Messages
  written by an older version (with envelope) in a running broker are dropped as messages without id.
- **D32** `register` publishes in the request: a publish failure is a 500 (was 201 and a lost message, D3).
- **D33** Manual update `POST /<provider>/update` answers 200 (was 201); the pending registrations are
  `GET /<provider>/registrations` (was `POST /<provider>/findAll` with 201) and list
  `{"registration": <the request as received>, "blocked": ...}` (was `{id, paymentType, blocked}`).
- **D34** Each queue has its own consumer channel; at the minute reset only the consumer of a queue that held messages
  restarts (was: one channel for both queues, closed and reopened). The broker's consumer cancellation no longer pauses
  a queue.

## 7. Test plan

Unit tests (no containers, virtual time with `kotlinx-coroutines-test`):

1. **Config / coercion**: `defined-variables` JSON is byte-equivalent to §2.2 (key order). Kotlin coercion (D17).
   An unknown key fails after the earlier keys are applied. Env values for the defined variables replace the defaults
   (S4). Env mapping of `RABBITMQ_URL`/`PAYMENT_URL`/`SHIPMENT_URL`/`RETRY_COUNT`, start-up failures (D18).
2. **Consume path**: a message is acked, a callback is scheduled after exactly `delay*1000` ms of virtual time (not
   before), the outcome draws come from an injected random (`r < rate` gives success, the boundary `r == rate` gives
   failure), and the payment/shipment callback URL and body are exact.
3. **Delay read time**: the processing time changes between consume and fire and the old delay is still used. The
   success rate changes during the delay and the new rate is used.
4. **Rate limit**: with `max=2`, the 3rd message is held (not acked) and `processingAllowed=false`. Reset gives a
   reconnect request, counts go to 0, and the requeued messages are processed. Blocked messages are not counted. `max=0`
   gives no reconnect. `max=NaN` gives no limit.
5. **Blocked/manual update**: register → update → consume gives exactly one callback (the manual one) and the message is
   not counted. Update after consumption gives 404 "Shipment not found". Duplicate registrations (Q13).
6. **Callback retries**: failing endpoint, `RETRY_COUNT=3`, gives 4 attempts at 0, 5, 10, 15 s of virtual time and then
   stops. `RETRY_COUNT=0` gives 1 attempt. Non-numeric gives 1 attempt. A 2xx on the 2nd attempt stops.
7. **Id truthiness** (Q8) and fatal handler calls: unparseable content, missing id at fire time.
8. **Delay bound**: 0 and negative processing times give no delay (D21).

HTTP/controller tests (WebTestClient, broker mocked):

9. Every route's status code and body: 201 with an empty body, `findAll` arrays, 400 for missing/invalid fields, 404
   for unknown routes (`POST /register`, `GET /dapr/subscribe`) and unknown registrations, `/health`, a non-JSON
   content type giving 415, extra fields passed through to the published message.

Integration tests (one Testcontainers RabbitMQ, image `rabbitmq:3-management` as in compose; callbacks captured by a
JDK `HttpServer`; stopped after use):

10. Payment and shipment round trip with `*_PROCESSING_TIME=0` and `*_SUCCESS_RATE=1`/`0` set through `POST /ecs/variables`:
    callback bodies exact, wire message format `{"pattern":"register-payment","data":{…}}` with deliveryMode 1, queues durable.
11. Rate limit with a real broker: `PAYMENTS_PER_MINUTE=1`, two registrations, and the reset triggered by hand: the second
    is held unacked, and after the reset it is requeued with `redelivered=true` and processed.
12. Messages already in the queue at startup (published straight to the broker) are processed. `PROCESSING_TIME=1` gives
    a callback between ≥1 s and ≈1.5 s.
13. Startup ordering: the HTTP port only opens after the broker connection (smoke check with the broker started late is optional).

Drop-in check (manual, optional, not on the measured stack): run the service image against a scratch RabbitMQ and stub
callback servers, and check `curl` health and the `/ecs` endpoints through an ECS container.

## 8. Implementation status (2026-11-01, after the unification)

- Code: `services/simulation` (package `org.misarch.simulation`; overview in `services/simulation/README.md`).
- Layout: `controller/` (`ProviderController` and its `/payment`, `/shipment` subclasses), `service/`
  (`RegistrationService<R>` with the in-memory registrations, `ProcessingService`, `CallbackService`), `amqp/`
  (`RegistrationListener`: the two `@RabbitListener`s and the minute reset), `ecs/`, `config/` (configuration
  properties, beans incl. queues and the two registration services, `SimulationScope`), `model/` (DTOs, queues).
- Tests: 48 green (`tools/build.sh :services:simulation:test`).

  | Group | Tests | Covers |
  |---|---|---|
  | `ExperimentConfigVariablesTest` | 5 | test plan 1 (ECS) |
  | `ConfigurationPropertiesTest` | 2 | test plan 1 (env mapping, start-up failures) |
  | `ProcessingServiceTest` | 15 | plans 2–5, 7, 8 (virtual time) |
  | `CallbackServiceTest` | 4 | plan 6 |
  | `PaymentControllerTest`, `ShipmentControllerTest` | 8 + 4 | plan 9 |
  | `RabbitAdapterTest` | 1 | wire format, transient message, durable queue |
  | `SimulationIntegrationTest` | 9 | full app + RabbitMQ: round trips, real-broker rate limit with manual reset, manual update of a held message, poison messages, backlog delivered at once (unlimited prefetch) and redelivered after the reset, recovery after a broker-side connection close, health/ECS order/404s, 400 for a missing `amount` |

- Image: `tools/image.sh simulation` → `misarch-kotlin/simulation:dev`. The image smoke test of the plain-client
  version (port closed until the broker is up, held messages unacked, exit code 1 when the broker stops) is not
  repeated for the Spring AMQP version; the Testcontainers tests cover held messages and recovery.
- Test plan items not automated: 13 was checked manually with the image. The `redelivered=true` flag (11) is not
  asserted, because the consumer does not pass it on; the in-order redelivery is asserted instead.
