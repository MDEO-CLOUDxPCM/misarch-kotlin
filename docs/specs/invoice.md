# Port spec: `invoice`

Original: `/home/devbox/workspace/misarch/invoice` (Rust 2024 edition, axum 0.8.3, async-graphql 7.0.16,
mongodb driver 2.8, bson 2.14, chrono 0.4.40, reqwest 0.12). Crate `misarch-invoice` (Rust ident `misarch_invoice`).
Reference SDL: `reference/sdl/invoice.graphql` (captured from the running original); it matches the code and `misarch/schemas/invoice.graphql`.

**FIXED (docs/fixes/invoice.md): the port now creates one invoice per order and publishes `invoice/invoice/created`; D1-D6 are fixed.** The rest of this spec describes the original. The code is the truth. Note: **no invoice is ever created and no event is ever published**. This holds in the deployed system and in fact
for any database content. Several independent defects block it (§8). The intended creation logic is still specified
(and ported as unit-tested functions), so that the dead path stays documented.

## 1. Role in the system

- Owns `Invoice` (rendered markdown `content` per order) and contributes `Order.invoice` to the federated graph.
- Wants replicas of User (name + addresses) and VendorAddress, fed by events, and the order snapshot from
  `discount/order/validation-succeeded`.
- Would publish `invoice/invoice/created`. It posts to pub/sub component **`invoice`**, topic `invoice/created`,
  and that component does not exist.
- No authorization anywhere. The GraphQL handler does not even read `Authorized-User`.

## 2. Runtime contract (must stay identical)

| Item | Value |
|---|---|
| HTTP port | 8080 (bind `0.0.0.0:8080`, hard-coded) |
| GraphQL | `POST /` (gateway `routing_url: http://localhost:3500/v1.0/invoke/invoice/method/`). `GET /` serves GraphiQL. Single requests only (a batch → 400). Response is always HTTP 200 JSON. |
| Health | `GET /health` → 200, empty body (healthcheck `wget -qO - http://localhost:8080/health`) |
| Dapr app-id | `invoice` (app-port `${EXPERIMENT_CONFIG_SIDECAR_PORT:-8080}`, http, Dapr HTTP 3500) |
| Dapr endpoints served | `GET /dapr/subscribe`; POST `/on-discount-validation-succeded`, `/on-vendor-address-creation-event`, `/on-user-creation-event`, `/on-user-address-creation-event`, `/on-user-address-archived-event`. **Not** served: `/on-id-creation-event` (→ 404, see D1). |
| Dapr endpoints called | `POST http://localhost:3500/v1.0/publish/invoice/invoice/created` (hard-coded host and port; component `invoice`, topic `invoice/created`; D4) |
| Env vars | `MONGODB_URI` (required, panics when unset; DB `invoice-database`, app name `Invoice`) → Postgres variables of docs/PORTING.md. OTLP metrics endpoint is **hard-coded** `http://otel-collector:4318/v1/metrics` (no env var; `OTEL_EXPORTER_OTLP_ENDPOINT` is not read), push every 5 s, service name `invoice`. The compose file sets no OTEL variable. |
| CLI | `--generate-schema` writes `./schemas/invoice.graphql` (build-time only). |

## 3. GraphQL schema (equals the reference SDL)

- `Query.invoice(id: UUID!): Invoice!` ("Query for invoice of specific UUID.")
- `Query._entities(representations: [_Any!]!): [_Entity]!`, `union _Entity = Invoice | Order | UserAddress | VendorAddress`
- `type Invoice @key(fields: "id")` ("Invoice of an order."): `id: UUID!`, `orderId: UUID!`, `issuedAt: DateTime!`,
  `content: String!`, `userAddress: UserAddress!`, `vendorAddress: VendorAddress!`, `vatNumber: String` (fields have no descriptions)
- `type Order @key(fields: "id")` ("Foreign type of an order."): `id: UUID!` ("UUID of the order."), `invoice: Invoice!` ("Invoice of the order.")
- `type UserAddress @key(fields: "id", resolvable: false)`, `type VendorAddress @key(fields: "id", resolvable: false)`: `id: UUID!`
- No mutations. Scalars `UUID`, `DateTime` (output: chrono `to_rfc3339()`, `+00:00`, AutoSi; ms precision → `…:56.789+00:00` or `…:56+00:00`).

## 4. Resolvers

`<IT>` = `misarch_invoice::graphql::model::invoice::Invoice`.

| Operation | Behaviour |
|---|---|
| `invoice(id)` | invoice by id; missing (or undeserializable) → ``<IT> with UUID: `<id>` not found.`` → `data: null` |
| `_entities Invoice {id}` | same as `invoice(id)`; the error fails the whole `_entities` field → `data: null` |
| `_entities Order {id}` | first invoice with `orderId = id` (natural/insertion order; several invoices per order are possible, see §5) → `{id, invoice}`. None → ``Invoice with order_id UUID: `<id>` not found.`` (fails the whole `_entities`) |
| `_entities UserAddress/VendorAddress`, unknown typename, missing/unparsable `id` | `Entity not found.` (fails the whole field). A missing `__typename` → `"__typename" must be an existing string.` |

The invoices table is always empty (no code path can insert, §5.2 step 3). So `order { invoice { … } }` through the gateway always produces
the `Invoice with order_id …` error.

## 5. Event handling

### 5.1 Subscriptions (`GET /dapr/subscribe`, exact JSON and order)
```json
[{"pubsubName":"pubsub","topic":"discount/order/validation-succeeded","route":"/on-discount-validation-succeded"},
 {"pubsubName":"pubsub","topic":"address/vendor-address/created","route":"/on-vendor-address-creation-event"},
 {"pubsubName":"pubsub","topic":"user/user/created","route":"/on-id-creation-event"},
 {"pubsubName":"pubsub","topic":"address/user-address/created","route":"/on-user-address-creation-event"},
 {"pubsubName":"pubsub","topic":"address/user-address/archived","route":"/on-user-address-archived-event"}]
```
Common rules for every POST route (axum `Json<Event<T>>`, envelope `{topic: string, data: T}`, extra keys ignored):
non-JSON content type → 415; bad JSON → 400; shape mismatch (missing/mistyped field, bad uuid, unknown enum
variant, negative number for an unsigned field) → **422**; body > 2 MiB → 413; `topic` differs from the route's
expected topic → **500**; handler failure → 500 (empty body); success → 200 `{"status":0}`.
serde rules: `String` rejects null/missing; `Option<…>` accepts null or missing; `u64` rejects negatives and fractions;
`DateTime<Utc>` = an RFC 3339 string with any offset, converted to UTC.

### 5.2 `POST /on-discount-validation-succeded` (topic `discount/order/validation-succeeded`)
`data = {order: OrderEventData}`. OrderEventData (camelCase): `id` uuid, `userId` uuid, `createdAt` DateTime,
`orderStatus` ∈ **`"Pending"|"Placed"|"Rejected"`** (serde variant names), `placedAt` DateTime (required),
`rejectionReason` ∈ `"InvalidOrderData"|"InventoryReservationFailed"`|null, `orderItems` [OrderItemEventData],
`shipmentAddressId`, `invoiceAddressId`, `paymentInformationId` uuid, `compensatableOrderAmount` u64,
`paymentAuthorization` = null or **`{"cVC": u16}`** (externally tagged enum, rename_all camelCase), `vatNumber` string|null.
OrderItemEventData (camelCase): `id`, `createdAt`, `productVariantId`, `productVariantVersionId`, `taxRateVersionId`,
`shoppingCartItemId`, `count` u64, `compensatableAmount` u64, `shipmentMethodId`, `discountIds` [uuid].

**D2**: discount publishes `orderStatus: "PLACED"` (Jackson enum name) and `paymentAuthorization: {"cvc": n}`.
So every real event is answered **422** and redelivered by Dapr forever. Nothing is stored.

If the payload deserializes and the topic matches, then `Invoice::new` runs. Any failure in it → 500:
1. `issuedAt` = now (ms); `issuedAtString` = UTC `%Y-%m-%d %H:%M:%S`.
2. Items table: `"| Item UUID | Product variant UUID | count | Compensatable amount |\n| --- | --- | --- | --- |\n"` plus,
   per item, `"| {id} | {productVariantId} | {count} | {compensatableAmount} |\n"`.
3. **Address lookup**: `find_one` on the user collection with filter `{addresses: {$elemMatch: {_id: invoiceAddressId}}}`
   and projection `{"addresses.$": 1, "_id": 1}`, deserialized as `User`. None → ``Address of UUID: `<id>` not found.``
   **D3**: `addresses` is always `[]`, because the address events write to `user_addresses`. **D6**: even if a user did
   match, the projection drops `first_name`/`last_name`, which `User` requires. Deserialization then fails.
   **So this step always fails: `Invoice::new` can never succeed, whatever the database contains.** Steps 4–9 are
   dead code. They are specified (and ported, unit-tested as functions) only to document the intended behaviour.
4. Vendor address: the first document of `vendor_address` (natural order). None → `Vendor address is not set locally.`;
   a document missing fields (always the case for event-created ones, §5.3) → deserialization error.
5. User `userId` from the user collection → missing → error.
6. `vatNumber` shown = `order.vatNumber ?? "-"`; stored `vatNumber` = the raw option.
7. `content` = exactly (note the leading newline, the trailing space after the issued-at value, and the final newline):
```
\n# Invoice\n\n### Company information:\n{v.companyName}\n{v.street1}, {v.street2}\n{v.city}, {v.country}\n\nVAT number: {vat}\n\n### Customer information:\nID: {user.id}\nName: {user.firstName}, {user.lastName}\nAddress:\n{ua.companyName}\n{ua.street1}, {ua.street2}\n{ua.city}, {ua.country}\n\n### Invoice ID: {invoice.id}, issued at: {issuedAtString} \n\nTerms and conditions: This invoice is created according the the companies terms and conditions specified on the website.\n\n---\n\nPurchased items overview:\n\n{itemsTable}\n\n---\n\nTotal compensatable amount: {compensatableOrderAmount}\n
```
8. Insert `{id: new v4, orderId: order.id, issuedAt, content, userAddress (full embedded copy), vendorAddress (full copy), vatNumber}`
   (failure → 500). There is no unique key on `orderId` (a redelivery would create a second invoice).
9. Publish (after the insert): `POST http://localhost:3500/v1.0/publish/invoice/invoice/created`,
   `Content-Type: application/json`, body
   `{"order": <OrderEventData re-serialized>, "invoice": {"orderId": uuid, "issuedAt": <chrono serde: RFC 3339 AutoSi with "Z">, "content": string}}`.
   Re-serialization keeps the struct field order shown above. Options are emitted as `null`, the enums as serde names
   (`"Placed"`, `"InvalidOrderData"`), `paymentAuthorization` as `{"cVC": n}`, and timestamps as `…Z` with 0/3/6/9 fraction
   digits. Any HTTP response (including Dapr's 4xx for the missing component) counts as success. Only a transport error → 500
   (the invoice would stay inserted).
10. 200 `{"status":0}`.

### 5.3 `POST /on-vendor-address-creation-event` (topic `address/vendor-address/created`)
`data` (**snake_case**, no rename): `id`, `street1`, `street2`, `city`, `postal_code`, `country`, `company_name` (all String,
required). **D5**: address publishes camelCase (`postalCode`, `companyName`, plus a nullable `name`), so every real event → **422**.
With a snake_case payload: upsert `{_id: id}` **only**. The `$set` stores no other field, so the document can never be
read as a vendor address (§5.2 step 4). Repeat → no change, 200.

### 5.4 `POST /on-user-creation-event` (topic `user/user/created`)
**D1**: the subscription's route is `/on-id-creation-event`, which does not exist. Dapr gets **404** and drops the event, so
this handler is unreachable through Dapr. Direct call: `data` **snake_case** `id`, `first_name`, `last_name` (the user service
sends camelCase → 422). It inserts `{_id, first_name, last_name, addresses: []}` (duplicate → 500).

### 5.5 `POST /on-user-address-creation-event` (topic `address/user-address/created`)
`data` camelCase: `id`, `street1`, `street2`, `city`, `postalCode`, `country`, `companyName` (string|null), `userId`.
This matches the real payload (`name` is ignored). `$push` onto **`user_addresses`** of user `userId`:
`{_id, street1, street2, city, postal_code, country, company_name: companyName ?? "", user_id}`. No upsert: an unknown user → no-op.
→ 200. In the deployment no user rows exist, so this is always a no-op 200.

### 5.6 `POST /on-user-address-archived-event` (topic `address/user-address/archived`)
`data` camelCase `id`, `userId`. `$pull: {"user_addresses._id": id}` on user `userId`:
an unknown user, or a user without a `user_addresses` field → no-op 200; a user **with** a `user_addresses` array → MongoDB
rejects traversing the array with the path part `_id` → 500 (unreachable in the deployment).

## 6. Persistence (MongoDB → Postgres)

| Mongo (DB `invoice-database`) | Table | Columns |
|---|---|---|
| `invoices` | `invoices` | `id uuid PK`, `order_id uuid NOT NULL` (index, not unique), `issued_at timestamptz NOT NULL` (ms), `content text NOT NULL`, `user_address jsonb NOT NULL`, `vendor_address jsonb NOT NULL`, `vat_number text NULL`, `seq bigserial` (natural order for the `orderId` lookup) |
| `vendor_address` | `vendor_address` | `id uuid PK`, `street1`, `street2`, `city`, `postal_code`, `country`, `company_name` (`text NULL`; event-created rows only have the id), `seq bigserial` (natural order) |
| `user` | `users` (`user` is reserved in Postgres) | `id uuid PK`, `first_name text NOT NULL`, `last_name text NOT NULL`, `addresses jsonb NOT NULL DEFAULT '[]'`, `user_addresses jsonb NULL` |

Embedded address JSON keys (snake_case as in Mongo): `_id`, `street1`, `street2`, `city`, `postal_code`, `country`,
`company_name`, `user_id` (vendor: no `user_id`).

## 7. Config and startup
The DB connection is lazy (it starts without a DB). No other env vars. OTLP push target as in §2. The port keeps
`http://otel-collector:4318` as the default endpoint.

## 8. Quirks and defects (originally reproduced; FIXED ones see docs/fixes/invoice.md)

- D1 **FIXED** (route served, camelCase accepted, idempotent). `user/user/created` routed to `/on-id-creation-event` (404, dropped). The handler itself expects snake_case.
- D2 **FIXED** (Jackson enum names, optional fields, `cvc`). validation-succeeded payload never deserializes (`"PLACED"` vs `"Placed"`, `cvc` vs `cVC`) → 422, endless redelivery.
- D3 **FIXED** (own `user_address` table, V2 migration). `addresses` vs `user_addresses` field mismatch: the invoice address lookup never matches.
- D6 **FIXED** (names from the user replica, fallback addressee name). the lookup's projection drops `first_name`/`last_name`, so even a match fails to deserialize. Together with D3:
  `discount/order/validation-succeeded` with a deserializable payload and the right topic **always** ends in 500 after
  exactly one user-collection query. No invoice row and no publish, ever.
- D4 **FIXED** (`pubsub` / `invoice/invoice/created`, error answer fails the handler, insert+publish in one transaction). publish URL uses component `invoice` (topic `invoice/created`). Dapr answers 4xx and the error is ignored, so
  `invoice/invoice/created` is never delivered. Report 01 lists this as T28.
- D5 **FIXED** (camelCase accepted, all fields stored). vendor-address payload expects snake_case → 422 forever. The upsert stores only `_id` anyway.
- **FIXED**: archive marks the address, unique invoice per order. Archive `$pull` path error (§5.6). No unique invoice per order; the publish happens after the insert (duplicates on redelivery).
- (port) jsonb normalizes the key order of the embedded address sub-documents; never observable (only `_id` is read).
- Event handlers answer `{"status":0}`. The terms text has the typo "according the the companies".
- No authorization on invoice data.

### Not reproduced (documented deviations)
- Exact Rust/serde/Mongo error texts inside 4xx/5xx event responses (bodies are not read by Dapr). Status codes are reproduced.
- GraphQL scalar coercion wording, lenient UUID forms and GraphiQL: same rules as the other Rust ports (see order.md §12).
- Metrics via the stack's OTLP mechanism (names differ from `axum-otel-metrics`).

## 9. Test plan (proves equivalence)

Unit:
- Content rendering byte-for-byte against a fixture built from the Rust template (incl. `-` VAT, empty items table, trailing space).
- InvoiceCreated DTO JSON (field order, `cVC`, nulls, `Z` timestamps with 0/3 fraction digits, serde enum names).
- Strict event DTO deserialization matrix: real discount payload (`PLACED`) → 422; Pascal-case payload → OK;
  `{"cvc":1}` → 422; `{"cVC":1}` OK; missing `placedAt` → 422; negative count → 422; vendor camelCase → 422.

Integration (Testcontainers Postgres, fake Dapr HTTP server recording requests):
- `GET /dapr/subscribe` exact JSON; `POST /on-id-creation-event` → 404; `GET /health` 200.
- validation-succeeded: real payload → 422, no row, no publish; deserializable payload, empty DB → 500 (address not found);
  fully seeded DB (user whose `addresses` contains the invoice address, vendor row with all fields) → still 500 (D6),
  no invoice row, **no request to the fake Dapr**; wrong topic → 500.
- Unit-level only (dead path): content rendering and the publish call (exact URL `/v1.0/publish/invoice/invoice/created`
  and JSON; a 404 answer from Dapr counts as success; a transport error is a failure).
- vendor address: camelCase → 422; snake_case → row with only the id; then creation → 500.
- user creation route: camelCase → 422; snake_case → row; duplicate → 500.
- user address created: unknown user → 200 no change; known user → `user_addresses` appended (not `addresses`).
- archived: unknown user 200; user without `user_addresses` 200; with → 500.
- GraphQL: `invoice` unknown → exact error, `data: null`; seeded → all fields (DateTime `+00:00`); `_entities` Order
  (found, missing → error), Invoice, UserAddress → `Entity not found.`.
- SDL: `tools/sdl-diff.sh invoice` empty.

## 10. Port implementation notes (services/invoice)

- `org.misarch.common.rust` helpers (docs/PORTING.md §11); strict serde-like decoding of every event payload in
  `event/EventData.kt` (status codes of §5.1); `DatabaseClient` SQL in `persistence/InvoiceStore.kt`.
- The creation path (`InvoiceService.createInvoice`) keeps the original's order of steps; D6 is modelled by the
  projected lookup result (`ProjectedUser`) never carrying the names, so it fails at the same point as the original
  (after exactly one `users` query). Rendering and the event JSON are tested as functions.
- Performance-relevant: deserializable validation-succeeded deliveries cost one SQL query each (Mongo: one
  `find_one`); real deliveries are rejected before any I/O (422), like the original. Vendor-address deliveries → 422
  without I/O; user-address deliveries → one `UPDATE` matching no row (Mongo: one `update_one`).
- OTLP: set `OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318` in deployments (the original hard-codes it).

## Integration fixes (2026-10-13, docs/INTEGRATION.md §4.1)

Found by the whole-system differential run against the running original:
- F5: `GET /health` answers 200 with an empty body like the Rust original (actuator web endpoints off).
