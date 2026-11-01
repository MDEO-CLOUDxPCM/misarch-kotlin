# simulation — defect fixes

The faithful version (commit `060498d`, spec `docs/specs/simulation.md`) reproduced the defects of the original NestJS
stub of the external payment/shipment provider. RabbitMQ, the queue names, the message format, the HTTP routes and
the timing semantics (processing time, success rate, per-minute rate limit, callback retries) are unchanged.

Tests: 56 before, 56 after (`tools/build.sh :services:simulation:test`; four tests of the reproduced crash paths were
replaced by three tests of the fixed behaviour, plus one real-broker poison-message test). Since the unification
(2026-11-01, spec §6.1 D10–D24) the fixes below hold unchanged except where noted; 52 tests.

## Fixed

### S1 — poison messages crashed the process (quirk Q9)

- **Symptom:** an unparseable queue message (or JSON `null`) threw out of the amqplib callback, the process crashed
  without acking, the broker redelivered the message after the restart: a crash loop that stopped all payment and
  shipment callbacks. A message without `data` or without an id crashed it as well (after the ack, or when the timer
  fired). Only possible with messages not produced by `register` (other producers, manual publishing, corruption).
- **Fix:** such a message (since the unification also one whose id is not a non-empty string) is acked, logged
  (`Dropping …`) and dropped; it is not counted against the rate limit and
  gets no callback. Broker connection/channel losses and a failed minute-boundary reconnect are still fatal (the
  container restarts, as before).
- **Tests:** `EventProcessorTest`: `unparseable messages are acked and dropped, not fatal (fix S1)`,
  `messages without data or without the queue's id are acked and dropped, not counted (fix S1)`;
  `SimulationIntegrationTest`: `poison messages in the queue are acked and dropped, later messages are processed`
  (real RabbitMQ).

### S2 — callback id taken from the wrong field (quirk Q8)

- **Symptom:** `id = data.paymentId || data.shipmentId`: a shipment registration with an extra `paymentId` field got
  its callback under that id (`/shipment/<paymentId>/status`), i.e. the shipment never got its status.
- **Fix:** the id is the field of the queue's kind (`paymentId` on `payments-queue`, `shipmentId` on
  `shipments-queue`), required (a non-empty string) before the message is processed (S1 otherwise).
- **Tests:** `callback id is the id of the queue's kind (fix S2)`.

### S3 — manual payment update answered "Shipment not found" (quirk Q5)

- **Symptom:** `POST /payment/update` for an unknown/consumed payment answered 404 `Shipment not found` (copy-paste
  from the shipment service, also in the log line and the comment "block shipment").
- **Fix:** `Payment not found` for payments (shipments unchanged). Same status code and body shape. Since the
  unification the 404 has Spring's error body (no message text, spec D10); the text is the
  `ResponseStatusException` reason and the log line.
- **Tests:** `PaymentControllerTest`: `manual update of an unknown payment is 404`, `ShipmentControllerTest`:
  `manual update of an unknown shipment is 404`.
- **Contract:** the 404 of `POST /payment/update` (manual/test route; no service calls it).

### S4 — env values of the defined variables were ignored (quirk Q1)

- **Symptom:** `PAYMENTS_PER_MINUTE`, `SHIPMENTS_PER_MINUTE`, `PAYMENT_PROCESSING_TIME`, `SHIPMENT_PROCESSING_TIME`,
  `PAYMENT_SUCCESS_RATE`, `SHIPMENT_SUCCESS_RATE` set in the environment never took effect: the defaults were written
  into the variable map at start-up, and lookups prefer the map over the environment (the original's
  `getCurrentVariableValue` clearly intends the environment as a source).
- **Fix:** at start-up, a non-empty env value of a defined variable replaces the default. Since the unification it is
  converted by Spring (`Int` / `Double`, `ExperimentConfigService`); a non-numeric value fails the start-up (before:
  `parseInt` / `Number`, a non-numeric value was ignored with a warning; spec D19). `POST /ecs/variables` still overrides at
  runtime; `GET /ecs/defined-variables` still reports the original defaults.
- **Effect on the system run:** none — `deploy/compose` sets none of the six variables (checked), so the defaults
  (10^6/min, 5 s, 0.95) apply as before. (The original k8s deployment sets `PAYMENTS_PER_MINUTE`/
  `SHIPMENTS_PER_MINUTE` = 10^7, which is also effectively unlimited, and `PROCESSING_TIME_SECONDS`, which is no
  defined variable and stays unused.)
- **Tests:** `ExperimentConfigVariablesTest`: `env values of the variables replace the defaults (fix S4)`,
  `a non-numeric env value fails`.

## Not fixed (deliberately)

| Item | Reason |
|---|---|
| Q7 ack before the processing delay (a restart loses pending callbacks) | part of the timing/queue semantics that the system run reproduces (no worker limit, pure-latency delay); acking after the delay would keep up to all in-flight messages unacked and change broker behaviour and the rate-limit requeue. A restart is rare in a run |
| Q4 `*_PER_MINUTE = 0` holds messages until a later minute hits the limit | only reachable through an explicit ECS experiment value; timing semantics of the original |
| Q13 duplicate registrations (list repository) | harmless: each message still gets one callback; registration ids are unique in practice |
| Q2 201 for every POST, Q3 integer processing time, Q6 manual update effectively always 404, Q10 count check before parsing, Q11 read times of the variables, Q12 callback retries on payment's 4xx/5xx, Q14 `findAll` semantics, Q15 partial ECS update + 500 | NestJS conventions / original timing semantics / wording; no wrong work in the buy flow |
| Crash on broker connection loss | the container restarts (`restart: unless-stopped`) like the original; recovering the consumer in-process would change the requeue semantics |
