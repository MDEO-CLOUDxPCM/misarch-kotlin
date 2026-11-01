# Service spec: `shipment`

Original: `/home/devbox/workspace/misarch/shipment` (Kotlin 1.9.23, Spring Boot 3.2.4, graphql-kotlin 7.0.2,
graphql-java 21.4, extended-scalars 21.0, R2DBC + infobip QueryDSL 9.0.2 (kapt), Flyway 9.22.3, Dapr Java SDK 1.11.0,
JDK 17). Package `org.misarch.shipment`. The deployed image `ghcr.io/misarch/shipment:main` contains exactly this code
(dependency versions checked from `BOOT-INF/lib` of the image jar).
Reference SDL: `/home/devbox/workspace/misarch/schemas/shipment.graphql`; it matches the code.

The code is the truth. Where `docs/.../services/shipment.mdx` disagrees, this spec follows the code (for example,
the docs name the field `ref` in `shipment-method/created`; the code sends `externalReference`).

## 1. Role in the system

- Owns `ShipmentMethod`, `Shipment` and the `Shipment`↔`OrderItem` join (`ShipmentToOrderItemEntity`).
- Keeps local replicas: `AddressEntity` (user and vendor addresses, from `address/*-address/created`),
  `ProductVariantVersionEntity(id, weight)` (from `catalog/product-variant-version/created`) and
  `OrderItemEntity(id, sentWithId, productVariantVersionId, quantity)` (from the `payment-enabled` payload).
- **Synchronous provider of the order service.** `order` calls `calculateShipmentFees` through Dapr service invocation
  (`POST localhost:3500/v1.0/invoke/shipment/method/graphql`, query in `order/queries/get_shipment_fees.graphql`) on
  every `createOrder`. The result is discarded by order, but a GraphQL error (data `null`) fails `createOrder`.
- Saga step: on `payment/payment/payment-enabled` it creates one shipment per shipment method used in the order. It
  registers each shipment with the external provider (the `simulation` stub) over direct HTTP, with retries
  configurable through experiment-config. It then publishes `shipment/shipment/created`.
- The provider calls back `POST /shipment/{id}/status` (direct HTTP, `SHIPMENT_URL=http://shipment:8080`, no Dapr).
  This publishes `shipment/shipment/status-updated` (consumed by return and inventory).
- On `return/return/created` it creates a return shipment to the current vendor address with the cheapest method.

## 2. Runtime contract (must stay identical)

| Item | Value |
|---|---|
| HTTP port | 8080 (Spring default) |
| GraphQL | `POST /graphql` (also `GET /graphql?query=`, batched array bodies); `GET /sdl`; `GET /graphiql` (graphql-kotlin defaults). Gateway routing URL `http://localhost:3500/v1.0/invoke/shipment/method/graphql` |
| Health | `GET /health` (actuator, `management.endpoints.web.base-path: /`, only `health` is exposed; `{"status":"UP"}`, 503 when the r2dbc indicator is DOWN). Docker healthcheck `wget -qO - http://localhost:8080/health` |
| Dapr app-id | `shipment`, app-port `${EXPERIMENT_CONFIG_SIDECAR_PORT:-8080}` (ECS proxy → 8080), dapr-http-port 3500 |
| Dapr endpoints served by app | `GET /dapr/subscribe`, `GET /dapr/config`, `GET /healthz`, actor routes (all from `dapr-sdk-springboot` `DaprController`) |
| Subscription routes | `POST /subscription/<topic>` for the 5 topics in §6.2 |
| Provider callback | `POST /shipment/{id}/status`, body `{"status":"PENDING"\|"IN_PROGRESS"\|"DELIVERED"\|"FAILED"}`, response 200 with an empty body |
| ECS endpoints | `GET /ecs/defined-variables`, `POST /ecs/variables` (§7) |
| Outbound HTTP | `POST ${misarch.shipment.provider.endpoint}` (env `MISARCH_SHIPMENT_PROVIDER_ENDPOINT`, compose value `http://simulation:8080/shipment/register`) |
| Pub/sub component | `pubsub`. Publishing goes through the Dapr SDK, by default over **gRPC** (`DAPR_GRPC_PORT`=50001). An HTTP publish to `:3500/v1.0/publish/pubsub/<topic>` with `Content-Type: application/json` produces the same CloudEvent |
| Env vars | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD` (also used for Flyway), optional `SPRING_FLYWAY_ENABLED`, **required** `MISARCH_SHIPMENT_PROVIDER_ENDPOINT` (`@ConfigurationProperties("misarch.shipment.provider") data class(val endpoint: String)`; startup fails when it is missing), `OTEL_*` for the Java agent (`OTEL_SERVICE_NAME=shipment`, …), Dapr SDK defaults `DAPR_HTTP_PORT`/`DAPR_GRPC_PORT` |
| Image | `java -javaagent:/home/java/opentelemetry-javaagent.jar -jar ./app.jar` (eclipse-temurin:17) |
| DB | Postgres 16, DB `shipment`, Flyway `classpath:db/migration`, `baselineOnMigrate(true)`, Flyway runs at startup through a custom bean (`initMethod="migrate"`, `@ConditionalOnProperty spring.flyway.enabled`, matchIfMissing) |
| Auth input | Header `Authorized-User: {"id":"<uuid>","roles":[…]}` (gateway) |
| Misc config | `spring.main.allow-bean-definition-overriding: true`; `graphql.packages: org.misarch.shipment`; `graphql.federation.enabled: true` |

## 3. GraphQL schema summary (reference SDL above; the service must produce it byte-identically from `/sdl`)

- `Query.shipmentMethods(first, skip, orderBy: ShipmentMethodOrderInput, filter: ShipmentMethodFilterInput): ShipmentMethodConnection!`
- `Query.shipmentMethod(id: UUID!): ShipmentMethod!`
- `Query.calculateShipmentFees(input: CalculateShipmentFeesInput!): Int! @inaccessible` (input type also `@inaccessible`)
- `Mutation.createShipmentMethod(input: CreateShipmentMethodInput!): ShipmentMethod!`
- `Mutation.archiveShipmentMethod(input: ArchiveShipmentMethodInput!): ShipmentMethod!`
- Types (all `implements Node @key(fields:"id", resolvable:true)`): `ShipmentMethod {id, name, description, externalReference,
  baseFees, feesPerItem, feesPerKg, archivedAt: DateTime, isArchived, calculateFees(items: [ProductVariantVersionWithQuantityInput!]!): Int!}`,
  `Shipment {id, status, shipmentMethod!, shipmentAddress: Address!, order: Order, return: Return,
  sentItems(first, skip, orderBy: OrderItemOrderInput): OrderItemConnection!}`, stub `Order {id, shipments(first, skip, orderBy, filter: ShipmentFilterInput): ShipmentConnection!}`,
  stub `Return {id, shipment: Shipment!}`, stub `OrderItem {id, sentWith: Shipment}`.
- `interface Address {id}` (description is the copy-paste "An item in an order."), `UserAddress`/`VendorAddress implements Address @key(fields:"id", resolvable:false)`.
- `interface Node`. Connections `ShipmentMethodConnection`, `ShipmentConnection`, `OrderItemConnection` are `@shareable` (`hasNextPage`, `nodes`, `totalCount`).
- Enums: `ShipmentStatus {PENDING, IN_PROGRESS, DELIVERED, FAILED}`, `OrderDirection`, `ShipmentMethodOrderField {ID}`,
  `ShipmentOrderField {ID}` (descriptions contain double spaces: "Shipment  order fields"), `OrderItemOrderField {ID}`.
- Filters: `ShipmentMethodFilterInput {isArchived: Boolean}`, `ShipmentFilterInput {status: ShipmentStatus}`.
- Federation 2.5 `@link(import: ["@shareable","@key","@inaccessible","FieldSet"])`. `_entities` union =
  `Order | OrderItem | Return | Shipment | ShipmentMethod | UserAddress | VendorAddress`. `didBuildSchema … clearDirectives()`.
  Scalars `DateTime` (extended-scalars `DateTimeScalar`) and `UUID` (`UUIDScalar`).

## 4. Resolvers and authorization (exact semantics)

Auth helper, identical to the other Kotlin services: `authorizedUser` throws `IllegalStateException("Unauthorized access")`
when the header is missing. `checkIsEmployee()` throws `"Unauthorized access: <id> is not an employee or admin"`
(employee = role `employee` or `admin`). A present but unparsable header fails context creation, so the request fails at HTTP level.

| Operation | Behaviour |
|---|---|
| `shipmentMethods` | **No auth.** Connection over all methods (archived included unless filtered). `filter.isArchived=true` → `archivedAt IS NOT NULL`, `false` → `IS NULL`. Order default `ID ASC`. |
| `shipmentMethod(id)` | **No auth.** `ShipmentMethodDataLoader` (batch `findAllById`). An unknown id gives NPE (`entities[it]!!`), which fails the **whole batch**. Error, `data: null` (non-null field). **FIXED (docs/fixes/shipment.md):** only the unknown key fails (`… : null`). |
| `ShipmentMethod.externalReference` | **Employee only** (`checkIsEmployee`). Non-null, so an unauthorized selection nulls the parent up to the nearest nullable ancestor. |
| `ShipmentMethod.isArchived` | `archivedAt != null`. |
| `ShipmentMethod.calculateFees(items)` | **No auth.** `calculateQuantityAndWeight(items)` and then the fee formula of this method (§5.3). Archived methods also compute. |
| `calculateShipmentFees(input)` | **No auth** (`@inaccessible`; only order calls it). Groups items by `shipmentMethodId` and loads the methods by id; a missing one gives `IllegalArgumentException("Not all shipment methods are valid")`. **Archived methods are accepted.** **FIXED:** an archived method is rejected with the same message ("can no longer be used"), so order's `createOrder` fails for it. The result is the sum over the groups of `fee(method, quantity, weight)` in Int arithmetic. An empty `items` list returns 0. |
| `createShipmentMethod` | **Employee.** The original's Mutation class is `@Transactional(REQUIRES_NEW)`; here the service method is `@Transactional` (same scope). Inserts the method (id generated by the DB default `uuid_generate_v4()`, `archivedAt=null`). Publishes `shipment-method/created` **inside** the transaction, so a publish failure rolls back. No validation (negative fees and empty strings are allowed; more than 255 chars gives a DB error). |
| `archiveShipmentMethod` | **Employee.** Same tx. `findById(id).awaitSingle()` (unknown id gives `NoSuchElementException`). Sets `archivedAt = OffsetDateTime.now()` (JVM zone) and saves (full-row UPDATE). Publishes `shipment-method/archived {id}`. **Re-archiving an archived method overwrites `archivedAt` and publishes again.** Returns the in-memory entity. |
| `Order.shipments(...)` | **No auth, no ownership check.** Shipments with `orderId = Order.id`, plus optional `status` filter; order `ID`. **FIXED:** the `status` filter works (binds the enum name). |
| `Shipment.shipmentMethod` | `ShipmentMethodDataLoader`. |
| `Shipment.shipmentAddress` | `AddressDataLoader`. Returns `VendorAddress` when `userId IS NULL`, else `UserAddress` (`__typename` decides). |
| `Shipment.order` / `Shipment.return` | Stub `Order(orderId)` / `Return(returnId)` or null. |
| `Shipment.sentItems(...)` | `OrderItemEntity INNER JOIN ShipmentToOrderItemEntity ON orderItemId = OrderItemEntity.id WHERE shipmentId = Shipment.id`, order `ID`. For return shipments this lists the returned items. |
| `Return.shipment` | `ShipmentRepository.findByReturnId(id)` (derived query, **not** a data loader). No row: null into a non-null Kotlin type, so NPE. More than one row: `IncorrectResultSizeDataAccessException`. Both give an error. **FIXED:** `findFirstByReturnId`; no row → `… : null` (SDL `shipment: Shipment!`). |
| `OrderItem.sentWith` | `ShipmentDataLoader.load(sentWithId)`, or null when the item is unknown. |
| `_entities` `Shipment` / `ShipmentMethod` | Data loaders. A null `id` resolves to null. An unknown id fails the batch (NPE). **FIXED:** only the unknown representation fails. |
| `_entities` `OrderItem` | `orderItemRepository.findById` **per representation** (not batched). Unknown id gives `OrderItem(id, sentWithId=null)` (no error). |
| `_entities` `Order` / `Return` | Stub objects, no DB access. A missing `id` gives an NPE in `UUID.fromString(null)`. |
| `_entities` `UserAddress` / `VendorAddress` | `resolvable:false`. No resolver is registered, so graphql-kotlin returns its "unable to resolve" error. |

Connections (`BaseConnection`, shared with all Kotlin services):
- `nodes`: `WHERE predicate AND filter` `ORDER BY <field exprs> <dir>` `OFFSET skip?:0 LIMIT first?:Long.MAX_VALUE`.
  Negative `first`/`skip` gives a Postgres error.
- `totalCount`: `count(pk)` with the same joins and condition, ignoring paging. Returned as an Int.
- `hasNextPage`: `false` if `first == null`; else whether a row exists at `OFFSET first+skip LIMIT 1` (no ORDER BY).
- Each field is a separate SQL query, and only the selected fields run.

## 5. Persistence and business logic

### 5.1 Schema (Flyway V1_1..V1_5, copy byte-identically; Flyway checksums must match)

Unquoted camelCase identifiers, so Postgres folds them to lowercase (`shipmententity.shipmentmethodid`, …):
- `AddressEntity(id UUID PK, userId UUID NULL, street1, street2, city, postalCode, country VARCHAR(255) NOT NULL,
  companyName VARCHAR(255) NULL (V1_2), version BIGSERIAL (V1_3))`
- `ProductVariantVersionEntity(id UUID PK, weight DOUBLE PRECISION NOT NULL)`
- `ShipmentMethodEntity(id UUID PK DEFAULT uuid_generate_v4(), name, description, externalReference VARCHAR(255) NOT NULL,
  baseFees, feesPerItem, feesPerKg INT NOT NULL, archivedAt TIMESTAMPTZ NULL)`
- `ShipmentEntity(id UUID PK DEFAULT uuid_generate_v4(), status VARCHAR(255) NOT NULL (enum name), shipmentMethodId FK,
  shipmentAddressId FK→AddressEntity, orderId UUID NULL, returnId UUID NULL)`
- `OrderItemEntity(id UUID PK, sentWithId FK→ShipmentEntity NOT NULL, quantity INTEGER NOT NULL (V1_4),
  productVariantVersionId UUID NOT NULL (V1_5; V1_4 added `productVariantVersion INTEGER`, V1_5 drops it))`
- `ShipmentToOrderItemEntity(shipmentId FK, orderItemId FK, PRIMARY KEY (shipmentId, orderItemId))` (V2: duplicate pairs
  removed, the unused `id` and the V1_6 `shipmentId` index dropped; originally `id UUID PK DEFAULT uuid_generate_v4()`
  without a unique constraint)
- Extension `uuid-ossp`.
- **Quirk:** `V1_1__create_relations.sql` ends with a stray `)` line after the last statement. It is in the
  deployed jar too. Flyway 9.22.3 accepts it, because the service runs. The new Flyway version must be verified
  to parse it the same way, without editing the file (that would change the checksum).

### 5.2 Writes

- Address events: native `INSERT INTO AddressEntity (id,userId,street1,street2,city,postalCode,country,companyName)`
  (no `ON CONFLICT`, so a redelivered event gives a PK violation and HTTP 500). `version` comes from the BIGSERIAL.
  `userId` is null for vendor addresses. **FIXED:** `ON CONFLICT (id) DO NOTHING` (200).
- PVV events: native `INSERT INTO ProductVariantVersionEntity (id, weight)` (no `ON CONFLICT`, so a duplicate gives 500). **FIXED:** `ON CONFLICT (id) DO NOTHING`.
- Order items: `INSERT … ON CONFLICT DO NOTHING`. `sentWithId` therefore stays the **first** shipment.

### 5.3 Fee formula (Int arithmetic, must stay bit-identical)

`calculateQuantityAndWeight(items)`:
1. `require(all quantity > 0)` → `"Quantity must be greater than 0"`.
2. Load the PVVs by id. `require(all known)` → `"Not all product variant versions are valid"`.
3. `weight = Σ pvv.weight * quantity` (Double, in item order) and `quantity = Σ quantity` (Int, wraps).

`fee = baseFees + quantity*feesPerItem + (weight*feesPerKg).toInt()`. Int overflow wraps. `Double.toInt()`
truncates toward zero, saturates at the Int bounds and maps NaN to 0. Summing the groups uses `Int.sum()` (wraps).

### 5.4 `createShipmentForOrder` (event `payment-enabled`; original: one `@Transactional(REQUIRES_NEW)` handler)

The handler groups `order.orderItems` by `shipmentMethodId` (first-occurrence order) and processes the groups
sequentially. For each group it builds `CreateShipmentInput(orderId, returnId=null, shipmentMethodId,
addressId=order.shipmentAddressId, items=(id, productVariantVersionId, count.toInt()))` and calls `createShipment`:
- `try { tryCreateShipment } catch (Exception) { publish creation-failed(reason = e.message ?: "Unknown reason") }`.
- `tryCreateShipment`:
  1. `require(address exists)` → `"Address does not exist"`.
  2. `shipmentMethodRepository.findById(...).awaitSingle()`. Unknown gives `NoSuchElementException` (kotlinx message).
     **Archived methods are accepted.**
  3. Insert `ShipmentEntity(PENDING)`.
  4. Insert order items (`ON CONFLICT DO NOTHING`).
  5. Insert join rows.
  6. Run `calculateQuantityAndWeight` (may throw), load the address and POST the provider definition with retries (§5.6).
  7. Publish `shipment/created`.
- **Quirk:** steps 3–5 are **not** undone when step 6 fails. The exception is caught, so the transaction commits.
  This leaves a PENDING shipment that was never registered with the provider, and a `creation-failed` event is
  published for it. `creation-failed` has no effective consumer (see 01-architecture T30).
  **FIXED (docs/fixes/shipment.md Q1/Q2):** the checks (steps 1, 2, quantity/weight, address) run before the inserts;
  one short transaction stores the shipments of all groups (3–5); each shipment is then registered at the provider
  outside a transaction and `shipment/created` is published (7). A failed registration removes the shipment, its
  order items and join rows again and publishes `creation-failed`. A failed check stores nothing.
- **FIXED:** idempotent - the storing transaction takes a transaction-scoped advisory lock on the order id and does
  nothing if shipments for the order already exist (the original created and registered a second set on a
  redelivery).
- **Quirk (FIXED, Q2):** events were published **before** commit, and the provider HTTP call (with retries and
  delays) ran while the DB transaction and connection were held.

### 5.5 `createShipmentForReturn` (event `return/created`; original: one `@Transactional(REQUIRES_NEW)` handler)

**FIXED (docs/fixes/shipment.md):** every error in steps 1–4 publishes `creation-failed` (with `returnId`; `shipmentAddressId`/
`shipmentMethodId` omitted while unknown) and the event is acknowledged (200); archived methods are not considered in
step 3; idempotent like §5.4 (lock on the return id, nothing done if the return already has a shipment).

1. Vendor address = `SELECT … FROM AddressEntity WHERE userId IS NULL ORDER BY version DESC LIMIT 1`.
   None gives `require` → `"No vendor address found"`. This is **not** caught, so HTTP 500 and Dapr redelivers.
   No creation-failed event.
2. `orderItems = findAllById(orderItemIds)` (unknown ids are silently dropped).
3. `findLeastExpensiveShipmentMethod`: considers **all methods including archived ones**
   (`require(nonEmpty)` → `"No shipment methods available"`). It computes `calculateQuantityAndWeight` over all items
   and returns `minBy(fee)` (the first in DB order on ties). Errors are not caught, so 500.
4. `CreateShipmentInput(orderId=null, returnId, method.id, vendorAddress.id, items)`. Its `init` requires a non-empty
   item list (`"orderItems must not be empty"`). That runs **outside** the try, so 500.
5. `createShipment` as in §5.4 without inserting order items.

### 5.6 Provider registration (`ShipmentProviderClient.registerShipment`; original: `sendShipmentToExternalProviderWithRetries`)

- `WebClient.create()` per shipment (default codecs, Jackson with **nulls included**). Body:
  `{"shipmentId":uuid,"ref":externalReference,"quantity":Int,"weight":Double,"address":{"street1","street2","city","postalCode","country","companyName"(nullable, emitted as null)}}`.
  `retrieve().toBodilessEntity()`: a 4xx/5xx or connection error is a failure. No timeouts are configured.
- `repeat(providerRetries) { try { POST; return } catch { log error; delay(providerRetryDelay ms) } }` and then one final
  POST **outside** try. So there are `providerRetries + 1` attempts and `providerRetries` delays, the last delay
  comes before the final attempt, and the final failure propagates (giving creation-failed). `providerRetries` is read
  once per shipment and `providerRetryDelay` at each failure.

### 5.7 `updateShipmentStatus` (`POST /shipment/{id}/status`; original: no transaction, here `@Transactional`)

`findById(id).awaitSingle()` (unknown gives 500). Sets the status (**any transition allowed**, including the same
status), saves, and publishes `status-updated` with `orderItemIds` from `ShipmentToOrderItemEntity WHERE shipmentId`.
Race (original): if the provider called back before the creating transaction committed, the result was a 500 and the
simulation retried (3 × 5 s). Since the fix (Q2) the shipment is committed before it is registered.

## 6. Events

Payload serialization: the Dapr SDK `ObjectSerializer` is a plain Jackson `ObjectMapper` with **`NON_NULL` inclusion**
and no Kotlin or JavaTime module. **Null fields are omitted** (`orderId` or `returnId`). UUIDs are strings, enums are
names, Doubles are JSON numbers.

### 6.1 Published

| Topic | Payload (null fields omitted) | When |
|---|---|---|
| `shipment/shipment-method/created` | `{id, name, description, externalReference, baseFees, feesPerItem, feesPerKg}` | createShipmentMethod |
| `shipment/shipment-method/archived` | `{id}` | archiveShipmentMethod |
| `shipment/shipment/created` | `{id, orderId?, returnId?, status:"PENDING", orderItemIds:[…], shipmentMethodId, shipmentAddressId}` | after provider registration succeeds |
| `shipment/shipment/creation-failed` | `{orderId?, returnId?, orderItemIds, shipmentMethodId, shipmentAddressId, reason}` (**FIXED:** `shipmentMethodId?`, `shipmentAddressId?` for early return-path failures) | exception in `tryCreateShipment` (**FIXED:** also return-path errors before it) |
| `shipment/shipment/status-updated` | `{id, orderItemIds, orderId?, returnId?, status}` | provider callback |

### 6.2 Consumed (`@Topic(pubsubName="pubsub")`, route `/subscription/<topic>`, body `CloudEvent<T>` decoded by Boot's ObjectMapper: unknown props ignored, Kotlin non-null params required)

| Topic | Fields used (required unless marked `?`) | Effect |
|---|---|---|
| `address/user-address/created` | `id, street1, street2, city, postalCode, country, companyName?, userId` (extra `name` ignored) | insert address |
| `address/vendor-address/created` | `id, street1, street2, city, postalCode, country, companyName?` | insert address, `userId=null` |
| `catalog/product-variant-version/created` | `id, weight` | insert PVV |
| `payment/payment/payment-enabled` | `order {id, userId, createdAt:String, orderStatus enum, placedAt:String, rejectionReason?, orderItems[{id, createdAt, productVariantVersionId, productVariantId, taxRateVersionId, shoppingCartItemId, count:Long, compensatableAmount:Long, shipmentMethodId, discountIds[]}], shipmentAddressId, invoiceAddressId, compensatableOrderAmount:Long, paymentInformationId, paymentAuthorization?{cvc:Int}, vatNumber?}` | §5.4 |
| `return/return/created` | `id, orderId, orderItemIds` (extra `reason`, `refundedAmount`, `createdAt` ignored) | §5.5 |

Status codes: success gives 200 with an empty body. A missing or ill-typed required field or a bad enum or UUID gives 400
(decoding). A service exception gives 500 (Dapr retries a non-2xx other than 404).
`/dapr/subscribe` lists the 5 subscriptions as Dapr SDK 1.11 `DaprTopicSubscription` JSON
(`pubsubName`, `topic`, `route`, `metadata`; nulls omitted). The ECS sidecar rewrites the routes to `/_ecs/pubsub/...`.

## 7. Experiment-config variables (service-owned)

- `GET /ecs/defined-variables` →
  `{"providerRetries":{"type":{"$schema":"http://json-schema.org/draft-07/schema#","type":"integer","minimum":0},"defaultValue":3},
    "providerRetryDelay":{"type":{…same…},"defaultValue":500}}`
- `POST /ecs/variables` with body `Map<String, Any>`. For each entry: `providerRetries`/`providerRetryDelay` → `value as Int`.
  A non-Int (Long > 2^31, double, null) gives a `ClassCastException`/NPE and 500. An unknown key gives
  `error("Unknown variable: <name>")` and 500. Entries are applied in order **until the first failure** (partial update).
  Success returns 200 with an empty body. The sidecar forwards all non-sidecar variables of the replica on every config event.
- In-memory, per replica, defaults 3 / 500. Not persisted.

## 8. Quirks to preserve (record, don't fix)

1. A provider failure or unknown PVV after the shipment insert leaves a committed PENDING shipment, and creation-failed is published (§5.4). **FIXED (docs/fixes/shipment.md).**
2. Events are published inside the DB transaction, before commit, and the provider HTTP call holds the transaction. **FIXED** (provider call outside the transactions, docs/fixes/shipment.md Q2).
3. Archived shipment methods are accepted by `calculateShipmentFees`, by order shipments and by return-shipment selection. **FIXED** for `calculateShipmentFees` and the return selection; order shipments still accept them (the order was accepted before the archiving).
4. Return path errors (no vendor address, no methods, no known items, unknown PVV) give 500 and endless Dapr redelivery, not creation-failed. **FIXED.**
5. Non-idempotent address and PVV inserts: a duplicate event gives a PK violation and 500. **FIXED** (plus idempotent `payment-enabled` / `return/created`).
6. `Order.shipments`, `Shipment.*` and `shipmentMethods` have no authorization. Only `externalReference` and the mutations need employee.
7. A data loader with an unknown id fails the whole batch (NPE). `Return.shipment` with no row gives an NPE error. **FIXED.**
8. Re-archiving is allowed and republishes `archived`. Status updates allow any transition.
9. Int overflow and truncation in the fee formula. `count: Long` is truncated with `toInt()`.
10. `ShipmentMethod.archivedAt` returned by the mutation is the in-memory `now()` (ns precision, JVM zone). Reads return the DB value (µs, UTC).
11. SDL descriptions with typos and double spaces ("Shipment  order fields", Address "An item in an order.").
12. `OrderItemEntity.sentWithId` stays the first shipment of an item (`ON CONFLICT DO NOTHING`).
13. `ShipmentFilterInput.status` never works. The enum parameter cannot be encoded, so `totalCount`/`nodes`/`hasNextPage` error (§9a). **FIXED.**
14. Every GraphQL response carries the `ftv1` federated-tracing extension (§9a).

## 9. Parts needing non-trivial migration

- **QueryDSL + infobip + kapt → template persistence.** The connection queries (filter, join for `sentItems`,
  count, hasNextPage), `findCurrentVendorAddress`, native inserts and derived queries (`findByReturnId`,
  `findByShipmentId`) must be re-expressed with identical SQL semantics (unquoted identifiers, enum stored as name).
- **Dapr SDK (`@Topic`, `DaprClient.publishEvent`, `DaprController`) → libs/common Dapr support.** The service must keep
  the `/dapr/subscribe` content, the `/subscription/...` routes, `NON_NULL` payload serialization, `/dapr/config` and `/healthz`.
- **`@Transactional(REQUIRES_NEW)` on suspend functions** (mutation class, two event handlers) must keep the
  "one transaction per handler invocation, publish before commit" behaviour.
- **graphql-kotlin 7 → current:** federation `@link` version and imports, the `clearDirectives()` hook, input
  `…Input` suffixing, `@Autowired` resolver-parameter injection, `FederatedTypeSuspendResolver`/`PromiseResolver`,
  `KotlinDataLoader` API and error message format must give an identical SDL and responses.
- **Flyway 9 → current:** needs the `flyway-database-postgresql` module, plus a check of the stray `)` in V1_1 and of checksum compatibility.
- **WebClient provider call:** the body must keep `companyName: null` (Jackson ALWAYS inclusion, not Boot's or Dapr's mapper).
  Retry or delay semantics, no timeouts.
- **DateTime scalar output** (extended-scalars version) must keep the same textual format (check against the original jar).
- ECS `value as Int` cast semantics (Jackson Integer vs Long) and the partial-apply behaviour.

## 9a. Observed on the original jar (oracle run 2026-10-13: image app.jar, JDK 21, Postgres 16, fake Dapr HTTP and provider)

- Flyway 9.22.3 applies V1_1..V1_5 including the stray `)`. Recorded checksums: 1.1=`1345248039`, 1.2=`-327098381`,
  1.3=`-881856978`, 1.4=`-889060718`, 1.5=`821113280`. The service must compute the same values.
- `/sdl` (raw graphql-kotlin printer output, `"…"` descriptions, `@link(import : [...], url : ...)`, includes `_service`,
  `@deprecated`/`@oneOf`/`@specifiedBy`) is **not** textually equal to `schemas/shipment.graphql`. The schemas repo copy is
  post-processed by `misarch/graphql-schema-transform` in CI. The raw output is saved as the SDL oracle.
- **Every GraphQL response carries `extensions.ftv1`** (Apollo federated-tracing protobuf, base64). graphql-kotlin 7 adds it
  without the `apollo-federation-include-trace` header. This is observable behaviour and costs CPU per request.
- Error shape: `{"errors":[{"message":"Exception while fetching data (/path) : <msg>","locations":[…],"path":[…]}],"data":…}`
  (no `extensions` on fetch errors). Validation errors have `"extensions":{}` and no `path`. Unknown id gives `… : null`.
  Unknown `Return.shipment` gives `… : Cannot invoke "org.misarch.shipment.persistence.model.ShipmentEntity.toDTO()"`.
  `_entities` UserAddress gives `Unable to resolve federated type, representation={__typename=UserAddress, id=…}` with location `-1/-1`.
- **`Order.shipments(filter:{status:…})` always fails**: `totalCount` and `nodes` give
  `Cannot encode parameter of type io.r2dbc.spi.Parameters$InParameter (PENDING)` (QueryDSL enum binding bug). Quirk to keep.
- Paging (QueryDSL): `first: 0` makes `nodes` fail with `"Limit must be greater than 0."`, a negative effective offset fails with
  `"Offset must not be negative."` (nodes and hasNextPage), and `first:-1` without skip makes hasNextPage fail on the offset.
  (Observed on tax, same `BaseConnection`.)
- A present but invalid `Authorized-User` header (e.g. `{"id":"x"}` or missing `roles`) gives **HTTP 400 with an empty body**.
- DateTime output: `2026-10-13T12:04:54.311Z` (milliseconds, `Z`).
- `/dapr/subscribe`: `[{"pubsubName":"pubsub","topic":"address/user-address/created","route":"/subscription/address/user-address/created","metadata":{}}, … payment-enabled, vendor-address, return, catalog PVV]`.
  `/dapr/config` → `{"entities":[]}`. `/healthz` → 200 with an empty body.
- Event and provider JSON exactly as in §6 and §5.6 (captured, e.g. `{"shipmentId":…,"ref":"ref-std","quantity":6,"weight":3.832,"address":{…,"companyName":null}}`).
  `creation-failed` example: `{"orderId":…,"orderItemIds":[…],"shipmentMethodId":…,"shipmentAddressId":…,"reason":"Address does not exist"}`.
- Spring error bodies for 4xx/5xx on REST routes: `{"timestamp":…,"path":…,"status":500,"error":"Internal Server Error","requestId":…}`.
- The return shipment picked the **archived** cheapest method, confirming quirk 3.

## 10. Test plan

Unit (no Spring):
- Fee formula: rounding (`toInt` truncation, negative weights), Int overflow, multi-group sums, empty list → 0.
- `calculateQuantityAndWeight`: quantity ≤ 0 → exact message; unknown PVV → exact message.
- Retry loop with a fake provider: `providerRetries = 0/1/3` → attempts = n+1, n delays, final exception propagates.
  Delay re-read per failure.
- ECS controller: defined-variables JSON shape; set Int; Long/double/null/unknown → error and partial apply.
- `CreateShipmentInput` invariants (xor, non-empty).

Integration (Testcontainers Postgres 16, one container per test class, stopped afterwards):
- Flyway: a fresh DB applies V1_1..V1_5 (including the stray `)`). Then a DB migrated by the **original jar's**
  Flyway 9 starts cleanly under the service (checksums, `baselineOnMigrate`). Compare the `information_schema` column list with the original.
- GraphQL through `WebTestClient` on `/graphql` with and without the `Authorized-User` header (buyer, employee, admin, malformed):
  shipmentMethods paging, filter `isArchived`, ordering, totalCount, hasNextPage; shipmentMethod unknown id → error;
  externalReference auth; create and archive (auth error messages, DB rows, re-archive); calculateFees and
  calculateShipmentFees (archived accepted, unknown method or PVV messages); `Order.shipments` with filter;
  `Shipment.sentItems` join; `Shipment.shipmentAddress` `__typename` (User vs Vendor);
  `Return.shipment` (found, none → error); `_entities` for all 5 resolvable types (unknown OrderItem → `sentWith: null`).
- Health: `/health` → `{"status":"UP"}`. `/sdl` equals `schemas/shipment.graphql` (**empty diff**).

Event tests (fake Dapr HTTP or gRPC endpoint capturing publishes, plus a fake provider HTTP server):
- `/dapr/subscribe` JSON equals the original's (5 entries).
- Each consumed topic: POST a CloudEvent with realistic upstream payloads (address with `name`, catalog PVV with all
  fields, Rust order JSON with snake→camel fields and `null` optionals, return with extra fields) → DB rows;
  duplicates → 500; missing required field → 400.
- payment-enabled with 2 shipment methods → 2 shipments, items, join rows, 2 provider POSTs (body JSON exact,
  `companyName: null`), 2 `shipment/created` (exact JSON, no `returnId`). Variants: unknown address →
  creation-failed `"Address does not exist"` and no rows; provider down → n+1 attempts, then creation-failed **and** the
  PENDING shipment row remains; unknown PVV → creation-failed and row remains.
- return/created: cheapest method including archived, vendor-address selection by `version`, event without
  `orderId`; no vendor address → 500 and no event.
- `POST /shipment/{id}/status` → DB status and `status-updated` JSON; unknown id → 500; bad status → 400.
- Mutations publish `shipment-method/created` and `archived` with exact JSON. A publish failure rolls back the insert.

Differential (optional, if resources allow): run the original jar (`ghcr.io/misarch/shipment:main` app.jar, JDK 21
works) and the service against two fresh Postgres DBs with the same fake Dapr and provider. Replay the scripted
scenario, then diff GraphQL responses (ids normalized), published events and DB dumps.

## 11. Status and deviations

Status: **done**. `tools/sdl-diff.sh shipment` is empty against `reference/sdl` and `--schemas`.
`tools/build.sh :services:shipment:test`: 17 green (13 integration, 3 unit, 1 SDL). Image `misarch-kotlin/shipment:dev`.

Verification beyond the tests: a **differential black-box run**. The same scripted scenario (address/PVV events,
mutations, queries, fees, payment-enabled with two methods, unknown address, provider callback, archive,
return, `_entities`, ECS) ran against the original jar (from `ghcr.io/misarch/shipment:main`) and against the service.
Each had a fresh Postgres 16, a fake Dapr HTTP API and a fake provider. GraphQL responses (ids/timestamps normalized),
error texts, HTTP status codes, published payloads, provider request bodies, the DB schema (`pg_dump -s`) and the Flyway
checksums are identical. The service also started on the DB that the original had migrated and filled
(Flyway validates and the data is served).

Mapping notes (no behavioural change):
- QueryDSL → `Condition`/`BaseConnection`. `sentItems` = `INNER JOIN ShipmentToOrderItemEntity j ON j.orderItemId = t.id`.
  QueryDSL's paging validation is reproduced with `PagingValidation.QUERYDSL` (the libs/common `BaseConnection` default).
- `ShipmentFilterInput.status` binds the enum itself, so it fails exactly like the original (quirk 13). (Fixed afterwards: binds the name.)
- `Mono.awaitSingle()` on an empty result throws `NoSuchElementException()` without a message, so unknown ids give
  `… : null` and creation-failed `reason: "Unknown reason"`. The service uses libs/common `findByIdOrThrow`.
- `ShipmentRepository.findByReturnId` is declared non-null like the original, so the error text for a return without a
  shipment is identical (`Cannot invoke "…ShipmentEntity.toDTO()"`).
- `extensions.ftv1` (federated tracing on every response) is **not** emitted: project decision for a uniform stack (docs/PORTING.md §7); the opt-in switch `misarch.graphql.legacy-federated-tracing` can restore it for experiments.
- `@Modifying` repository methods return the row count (`Long`) instead of `Unit`.

Deviations:
1. Events are published over the Dapr HTTP API (`POST /v1.0/publish/pubsub/shipment/shipment/created`, topic slashes
   not percent-encoded) instead of the Java SDK's gRPC client. Dapr produces the same CloudEvent. This is the accepted
   project-wide deviation.
2. `_entities` for the non-resolvable `UserAddress`/`VendorAddress` answers the same message but without
   `"locations":[{"line":-1,"column":-1}]` (graphql-kotlin 11). The gateway never asks for these (`resolvable: false`).
3. Exception texts that come from libraries rather than from the service code can differ in rare error paths,
   e.g. the `creation-failed.reason` when the provider fails (Spring 7 `WebClientResponseException` text) or DB errors.
   All texts produced by the service code are identical.
4. Runtime: JDK 25 / Spring Boot 4 / Reactor Netty 1.3 (project-wide). The provider call still creates a new
   `WebClient` per shipment and has no timeouts, like the original.
5. `/dapr/subscribe` lists the same 5 subscriptions. The array order may differ (bean-method order); the ECS and Dapr do not depend on it.
6. `ShipmentToOrderItemEntity` has the pair as primary key and no `id` column (V2, duplicate pairs removed). Not visible
   through the API; tools that write the table directly must not insert `id`.
7. `updateShipmentStatus` runs in a transaction (like every service method that writes and publishes): a failing
   `status-updated` publish rolls the status change back (the provider callback gets 500 and is retried), where the
   original kept the new status without the event. One BEGIN/COMMIT more per callback.
8. Transactions are on the service methods (`@Transactional`) instead of the `Mutation` class and the event handlers
   (`REQUIRES_NEW`); same scope, except for the shipment creation: the inserts are one short transaction, the
   provider registration runs after its commit (a stored shipment is visible through the API while it is being
   registered; a failed registration removes it again), docs/fixes/shipment.md Q2.

## Integration fixes (2026-10-13, docs/INTEGRATION.md §4.1)

Found by the whole-system differential run against the running original:
- F1: events are decoded with Jackson-2 semantics for primitives (`spring.jackson.deserialization.fail-on-null-for-primitives: false`): a missing/null primitive becomes 0/false instead of HTTP 400, like the original (Spring Boot 3.2).

## Fixes (2026-10-13, docs/fixes/shipment.md)

Defects of the original fixed here (state `faithful` = commit 060498d): idempotent replica inserts (quirk 5),
idempotent `payment-enabled`/`return/created` (no second set of shipments on a redelivery), no phantom PENDING
shipment on a failed creation (quirk 1), `creation-failed` + ack for return-path errors (quirk 4), archived methods
rejected by `calculateShipmentFees` and ignored for return shipments (quirk 3, partly), per-key data loader failures
and `… : null` for `Return.shipment` without row (quirk 7), working `ShipmentFilterInput.status` (quirk 13).
SDL, topics, routes and `POST /shipment/{id}/status` unchanged; `creation-failed` may omit `shipmentMethodId`/
`shipmentAddressId` (new return-path failures only). `tools/build.sh :services:shipment:test`: 18 green
(14 integration, 3 unit, 1 SDL).
