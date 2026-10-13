# Integration of the 17 ports (whole-system verification, 2026-10-13)

The 16 domain ports and the `simulation` stub were run together in `deploy/compose` (81 containers, same topology as
the calibration setup of the original) and compared with the original MiSArch (`misarch-optimization/calibration`),
one stack at a time on the same machine. Tooling: `deploy/integration/` (see §1). Raw results:
`deploy/integration/results/` (not versioned).

## 1. How to run

```
deploy/build-images.sh                                   # 17 images (tools/image.sh each)
deploy/compose/up.sh                                     # never together with the original (calibration/up.sh)
PY=/home/devbox/workspace/misarch-optimization/calibration/.venv/bin/python
$PY deploy/integration/kseed.py --out deploy/integration/state/state.json      # ~2 min, calibration seeder
deploy/integration/loadgen.sh kotlin plans/functional.json results/k_functional  # all request types, low rate
$PY deploy/integration/diffprobe.py run --prefix misarch-kotlin- --state deploy/integration/state/state.json \
    --out deploy/integration/results/k_probe.json        # differential probe (see below)
deploy/integration/perfsmoke.sh kotlin results/k_perf    # indicative performance smoke (~10 min)
deploy/compose/down.sh                                   # stop, keep volumes
# the same against the original (its own seeded state, results/seed/state.json of the calibration):
calibration/up.sh; deploy/integration/loadgen.sh original ...; diffprobe.py run --prefix misarch- \
    --state <calibration>/results/seed/state.json ...; perfsmoke.sh original ...; calibration/down.sh
$PY deploy/integration/diffprobe.py compare results/o_probe.json results/k_probe.json   # all differences
python3 deploy/integration/classify.py results/o_probe.json results/k_probe.json        # direct-probe diffs by category
python3 deploy/integration/cmpdirect.py results/o_probe.json results/k_probe.json       # direct-probe diffs, ftv1 filtered
deploy/compose/redeploy.sh order discount                # swap in rebuilt images (app + its two sidecars)
```

| Tool | What it does |
|---|---|
| `kseed.py` | runs the calibration `seed.py` unchanged (Keycloak admin API + GraphQL, same data model, seed 42) and swaps its three original-specific helpers: review defect workaround → rows in the review port's `product_variants` table, discount schema workaround (`workarounds/discount_usage_columns.sql`, the port keeps the snake_case upsert defect) → `misarch-kotlin-discount-db-1`, ack of known defect deliveries → `misarch-kotlin-dapr-redis-1` |
| `loadgen.sh` | the calibration `loadgen.py` (unchanged, 23 request types incl. the 4 sidecar-hop probes and `eventUserCreate`) with container IPs and its own state/pool/token files per stack (the calibration's `pools.json` is never touched) |
| `diffprobe.py` | one deterministic scenario, normalised (UUIDs, timestamps): `/health` and `/dapr/subscribe` of every app; ~190 GraphQL requests **directly to every subgraph** generated from `reference/sdl` (unknown/malformed ids on every single-id root field, `first: 0`/`-1`, `skip: -1`, missing auth, buyer vs admin, `_entities` with unknown keys, unknown field, syntax error, invalid `Authorized-User`); every loadgen request type once through the gateway plus federated joins (product page: catalog+review+inventory+discount+tax; current user: user+address+payment+order+wishlist+shoppingcart+review+notification+return+discount; order: order+user+address+catalog+shipment) and unknown ids through the gateway; the buy saga (one buyer per payment method, order/shipment/payment state polled 45 s); Redis stream deltas (published per topic, per consumer group read / still pending = failed deliveries) |
| `perfsmoke.sh` / `perfsummary.py` | loadgen `plans/perfsmoke.json` + the calibration's 1 Hz cgroup sampler `cpumon.py`; per phase CPU per container / class and latency per type |
| `deploy/compose/redeploy.sh` | recreates app + Dapr + ECS sidecar (the sidecars share the app's network namespace: recreating only the app leaves them broken) |

## 2. Bring-up (task 1)

All 17 images build (`deploy/build-images.sh`), the stack starts in ~2 min and every health check is green (81
containers, ~11.4 GB RSS: 17 JVMs at 380-420 MB, gateway 2.2 GB). Compose changes: `gen_compose.py` now generates the
RustFS block that had only been edited by hand into `docker-compose.yaml` (regenerating gives the same services);
`redeploy.sh` added. No other compose/config issue.

## 3. End-to-end functional check (task 2)

- **Seeding** with the calibration seeder: 3 tax rates, 3 shipment methods, 24 categories, 300 products / 508
  variants, 60 items per variant, 8 discounts, 2 coupons, 200 buyers (+ gatling admin), 600 reviews, 80 wishlists:
  2,280 GraphQL calls, 0 errors, 125 s (original: ~3 min). Every replication event fired (user/user/created to 9
  services, product/variant events to discount, inventory, review, shoppingcart, wishlist, order, shipment).
- **loadgen, all 23 request types**, 0.1-0.5 req/s each for 180 s (`plans/functional.json`): Kotlin 1,090 requests,
  **0 errors**; original 1,102 requests, 0 errors (same plan). Every type succeeds on both stacks.
- **Federation** through the gateway (product page, current user with 10 subgraphs, order joins): identical response
  shapes on both stacks, no errors.
- **Event flows** (Redis streams after the scenario, Kotlin): replication topics fully consumed; buy saga
  `order/order/created` → inventory `reservation-succeeded` → discount `validation-succeeded` → payment
  `payment-enabled` → shipment `shipment/created` + `status-updated` (→ inventory, return) — complete after fix F1.
  Pending (failing) deliveries exactly where the original fails: `catalog/product-variant/created`|review (defect D1,
  500 forever), `discount/order/validation-succeeded`|invoice (422 forever), `user/user/created`|invoice (404, dropped).
- **Async provider via simulation**: INVOICE → payment SUCCEEDED, shipment PENDING → DELIVERED after ~5 s;
  PREPAYMENT → payment stays PENDING (simulation callback, payment quirk 8), shipment DELIVERED after ~11 s;
  CREDIT_CARD → SUCCEEDED, shipment DELIVERED. Same sequences and timings as the original (§4).

## 4. Differential check against the original (task 3)

Same scenario on both stacks (`diffprobe.py`), the original with its calibration data set, the Kotlin stack with the
same seeder. Before the fixes of §5: 188 differences; after: see §4.3.

### 4.1 Bugs found and fixed (real port deviations)

| # | Finding | Fix |
|---|---|---|
| F1 | **The buy saga stopped at discount**: every `inventory/product-item/reservation-succeeded` got HTTP 400. The real order payload has `paymentAuthorization: {"cVC": 123}`; discount's DTO has `cvc: Int`. Jackson 2 (original, Spring Boot 3.2) ignores `cVC` and reads the missing primitive as 0 (the original re-publishes `{"cvc":0}`, `reference/events`); Jackson 3 (Spring Boot 4) enables `FAIL_ON_NULL_FOR_PRIMITIVES` by default. The port's test had used `{"cvc":123}`. | `spring.jackson.deserialization.fail-on-null-for-primitives: false` in all 8 Kotlin-original ports (Jackson-2 semantics of their originals); regression test with the real payload (discount) |
| F2 | `PagingValidation.QUERYDSL` was missing in catalog, discount, notification, return, user (`first: 0` → nodes `[]`, negative values → Postgres error; original: `Limit must be greater than 0.` / `Offset must not be negative.`) | override in all 18 connections + catalog's custom `CategoricalCategoryCharacteristicValueConnection`; test in catalog |
| F3 | Unknown ids: the original Kotlin services answer `Exception while fetching data (/x) : null` (`kotlinx.coroutines.reactor.Mono.awaitSingle`, verified for `return`, `currentUser`, `archiveUserAddress`, `updateProduct`, `updateUser`, `updateNotification`, `updateDiscount`, `updateCoupon`); address, catalog, discount, notification, return, user used `… : No value received via onNext for awaitSingle` (docs/requests/shipment.md §3 was right) | `NoSuchElementException()` without message everywhere; tests updated |
| F4 | order (Rust/async-graphql original): root field errors and `_entities` errors answered without `data`, `_entities` errors without path and with null elements; the original answers `"data": null`, first error only, path `["_entities"]` (same engine, async-graphql 7.0.16, as shoppingcart/wishlist) | order imports the new shared `AsyncGraphqlExecutionConfiguration` (libs/common, split out of `AsyncGraphqlConfiguration`); order tests updated to async-graphql semantics |
| F5 | `/health` of the Rust ports invoice, media, order, review answered `{"status":"UP"}` (actuator); originals: 200 with an empty body (shoppingcart/wishlist were already right) | `GET /health` → empty 200 in `RustCompatConfiguration` (review, invoice, media) and order's routes, actuator web endpoints off; tests assert the empty body |

### 4.2 Accepted differences (documented deviations, not bugs)

| Difference | Where | Reference |
|---|---|---|
| no `extensions.ftv1` (86 of the direct-probe differences are only this) | 8 Kotlin-original ports | PORTING.md §7, project decision |
| `_entities` errors of Kotlin-original ports have no `locations` | address, catalog, discount, notification, return, shipment, tax, user | graphql-kotlin 11; shipment.md deviation 2; the gateway never resolves unknown keys |
| request-level errors (syntax, unknown field, invalid UUID literal) use graphql-java texts; TS ports: HTTP 200 instead of Apollo's 400; Rust ports: request error instead of field error for a malformed UUID literal | inventory, payment, invoice, media, order, review, shoppingcart, wishlist | inventory D4, payment D2, shoppingcart D3, order §12 |
| no `extensions.stacktrace` in errors | inventory, payment | inventory D3, payment D2 |
| `getMedias` works (RustFS) - the calibration original has no object store (MinIO image unavailable) and fails | media | deploy/README.md |
| the saga runs faster: the gateway order in the probe already had a DELIVERED shipment after 8 s on Kotlin, none yet on the original (the original's shipments arrived later, same events) | order, inventory, payment, shipment | timing only |
| concurrent reservations of the same variant: one of three simultaneous orders failed with `WriteConflict` on Kotlin (Postgres `40001`), the original's Mongo transaction conflicts the same way (verified by the inventory port) | inventory | inventory spec §4.4, D2; the probe now uses one variant per order |
| `averageRating` is `f32`-rounded on both (`4.857142925262451`), integral averages print as `4` | review | data-dependent |

### 4.3 After the fixes

Rebuilt images, same probe (`results/k_probe3.json` vs `results/o_probe1.json`): no difference in `/health`,
`/dapr/subscribe`, the saga (same state sequences and timings per payment method: INVOICE/CREDIT_CARD shipment
PENDING at 1.6 s, DELIVERED at ~6.3 s, payment SUCCEEDED; PREPAYMENT PENDING at 6.5 s, DELIVERED at 11 s, payment
stays PENDING) and the events (same published counts per topic and the same consumer groups reading / failing;
only the time-dependent `heartbeat` count differs). The 163 direct-probe differences are all accepted ones
(`classify.py`): 100 `ftv1` only, 23 engine texts of request-level errors (Rust/TS ports), 20 missing
`stacktrace` extension (TS ports), 18 `_entities` errors without `locations` (Kotlin-original ports), 2 media
object store. Through the gateway: `stacktrace` (inventory), `averageRating` integral vs fractional (different
data), and `orderTracking` 8 s after `placeOrder`: the Kotlin order already had its shipment, the original's not yet
(first saga after a cold start of the original; the later saga orders have identical timings).
Final functional run (`results/k_functional_final`): 1,113 requests of all 23 types, 0 errors.

## 5. Cross-cutting consistency (task 4), decided by the running originals

| Item | Original | Ports (after F2-F5) |
|---|---|---|
| root error, non-null field | Kotlin originals: no `data` key; Rust (async-graphql): `"data": null` + first error; TS (Apollo): `"data": null` | identical per service (order fixed, F4) |
| `/health` | Kotlin: `{"status":"UP"}` (actuator); TS: `{"status":"OK"}`; Rust: 200, empty body | identical (invoice, media, order, review fixed, F5) |
| unknown id (Kotlin originals) | `Exception while fetching data (/x) : null` | identical (F3) |
| paging `first: 0` / negative (Kotlin originals) | QueryDSL messages | identical in all Kotlin-original connections (F2) |
| invalid `Authorized-User` | Kotlin: HTTP 400 empty body; Rust/TS: header ignored | identical |
| federated tracing `ftv1` | Kotlin originals only | off everywhere (decision) |

## 6. Deduplication (task 5)

- `org.misarch.common.rust.asyncgraphql` (libs/common): the former `compat/` packages of shoppingcart and wishlist
  (byte-identical apart from the package): async-graphql semantics instrumentation and `_entities` fetcher,
  no-batch parser, argument parse errors, lenient `Authorized-User` header, scalars, routes (`GET /`, `/health`,
  `/dapr/subscribe` from the service's resource), axum JSON handler. `AsyncGraphqlConfiguration` (shoppingcart,
  wishlist) imports `AsyncGraphqlExecutionConfiguration` (also used by order, F4).
- Merged into `org.misarch.common.rust` where provably identical: `RustUuid.tryParse`/`ORDER` (order's
  `parseRustUuid`/`UuidBytesComparator`), `RustDateTime` (order's `RustTime`), `dfe.rustAuthorizeUser`. Variants that
  behave differently (UUID length check, header duplicate keys, `_entities` concurrency, multipart, event JSON
  status codes) stay separate classes; order keeps its own scalars, header parser and event routes.
- `org.misarch.common.nest` (libs/common): `ClassValidator`, `NestExceptions`, `NestGraphQLErrors`, `RolesGuard`,
  `UuidScalar`, `Detached`, `Js` (payment's superset incl. `MongooseCast`), `NestWebConfiguration` for inventory and
  payment; `Selection.kt` (different fragment semantics) and payment's scalars stay local.
- Everything opt-in (`@Import`), no new auto-configuration. Details: PORTING.md §11, §11a, §12.
- Known flaky test (pre-existing, not changed): shoppingcart `a failing root field nulls data, reports only the first
  error…` sends two failing root fields; which error is "first" depends on which lookup completes first (also in
  async-graphql, which reports the first to complete).

## 7. Performance smoke (task 6) — INDICATIVE ONLY

Not a calibration: shared machine (other agents' builds/tests: 1.9-2.6 foreign cores during the windows, load
average ~8 on 8 vCPUs, swap in use), one run each, JVMs only briefly warm (180 s at 5 sessions/s before measuring),
Kotlin and original measured ~25 min apart. Plan: calibration Gatling mix (token, products, product, users, address,
createShoppingcartItem at 1/s per session level; shipmentMethods, paymentInformations, createOrder, placeOrder at
0.343/s), 45 s idle, 180 s at 3 and 6 sessions/s, k8s CPU limits (apps 0.5).

CPU ms per session above idle (stack cores at the level minus idle, divided by sessions/s):

| | original 3/s | Kotlin 3/s | original 6/s | Kotlin 6/s |
|---|---|---|---|---|
| **whole stack** | **250** | **172** | **254** | **182** |
| 16 apps + simulation | 131 | 100 | 125 | 94 |
| gateway (unchanged) | 33 | 31 | 31 | 33 |
| Dapr sidecars | 15 | 11 | 19 | 16 |
| experiment-config sidecars | 13 | 6 | 14 | 12 |
| databases (Mongo/Postgres) | 34 | 10 | 39 | 9 |
| keycloak (unchanged) | 18 | 16 | 18 | 16 |
| order (Rust → Kotlin) | 42 | 12 | 47 | 9 |
| inventory-db (Mongo → Postgres) | 18 | 1 | 20 | 1 |
| shoppingcart (Rust → Kotlin) | 1.4 | 12 | 1.4 | 10 |
| review (Rust → Kotlin) | 1.0 | 4.9 | 1.1 | 5.0 |
| catalog / user / shipment (Kotlin → Kotlin) | 16 / 14 / 16 | 13 / 11 / 14 | 13 / 13 / 12 | 13 / 13 / 10 |

Idle: original 0.57 cores, Kotlin 0.69 cores (17 idle JVMs). Latency p50 / p95 (ms) at 6 sessions/s:
createOrder original 352 / 1,276 vs Kotlin 52 / 132; placeOrder 86 / 482 vs 19 / 55; createShoppingcartItem 8 / 21
vs 14 / 49; products 9 / 31 vs 9 / 34; product 22 / 52 vs 20 / 64; users 8 / 24 vs 8 / 32; token 16 / 29 vs
16 / 29. No errors on either stack.

Reading: the language/stack effect is of the order of the per-service differences, not uniform. The Rust order
service is the original's bottleneck because of its implementation (whole cart read, a new HTTP client per sync
call, calibration README defect 7), not because of Rust; its Kotlin port is ~4-5x cheaper and createOrder ~6x
faster. The other way round, the Rust shoppingcart/review are 5-8x cheaper than their JVM ports. MongoDB
(inventory's reservation transactions) costs ~15x the Postgres equivalent. Kotlin-original services barely change
(JDK 25/Boot 4 vs JDK 17/Boot 3.2, no ftv1). Overall the Kotlin stack needs ~0.7x the CPU per session of the
original at these levels, with a higher idle floor.

## 8. Remaining differences

- All differences listed in §4.2 (accepted, documented in the specs).
- Shared defects of the original are reproduced on purpose and still need the calibration workarounds (review
  variants, discount snake_case upsert) and leave failing deliveries in Redis (review, invoice) - same as the original.
- Performance profile differs per service (§7), which is the point of the port; JVM idle floor is higher.
- Not verified at runtime (no request type exercises them): media uploads against RustFS (covered by the media
  tests against RustFS and MinIO), `return` creation (always fails in both), notification creation via events.
- Test suite: `tools/build.sh test` green, 668 tests (libs/common 31, address 11, catalog 23, discount 29, inventory
  179, invoice 11, media 19, notification 15, order 35, payment 141, return 18, review 23, shipment 17, shoppingcart
  20, simulation 56, tax 11, user 15, wishlist 14). Fixed on the way: a UUID-ordering flake in a discount test
  (`java.util.UUID.compareTo` is signed, Postgres orders bytewise) and a time-dependent crash of payment's SDL test
  context (the 15-minute cron fired without a database and the Node-like terminator halted the test JVM).

