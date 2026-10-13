# payment — port specification

Original: `misarch/payment` (TypeScript, NestJS 10.3, @nestjs/graphql 12.1 + @nestjs/apollo 12.1 (Apollo Server 4,
federation v2), Mongoose 8.2.0 on MongoDB 6, class-validator 0.14.1 / validator 13.11, @nestjs/schedule 4,
@nestjs/axios + axios 1.6, @dapr/dapr 3.3.1, graphql-type-json 0.3.2). Commit `5822993` (2025-06-29) = the
`ghcr.io/misarch/payment:main` image used by the calibration.

"Quirk" marks behaviour that looks wrong but must be reproduced. **Since the defect fixes (docs/fixes/payment.md)
items marked "FIXED (Pn)" describe the original's behaviour; the port now behaves as described there.** Everything was **verified against the original image**
run in an isolated network (own MongoDB, fake Dapr sidecar, fake simulation; recorded exchanges, §11).

## 1. Runtime contract

| Item | Value |
|---|---|
| Dapr app-id | `payment` |
| HTTP port | `8080` |
| GraphQL | `POST /graphql` |
| Health | `GET /health` → `200 {"status":"OK"}` |
| Subscriptions | `GET /dapr/subscribe` (§5.1) |
| Provider callback (served, plain HTTP, not via Dapr) | `POST /payment/update-payment-status` (§6.2), called by `simulation` at `${PAYMENT_URL}` = `http://payment:8080` |
| Provider calls (made, plain HTTP) | `POST ${SIMULATION_URL}/payment/register`, credit-card retry `POST ${SIMULATION_URL}/register` (§6.1) — FIXED (P2): the retry uses `payment/register` with the normal body |
| Dapr publish | `POST http://127.0.0.1:3500/v1.0/publish/pubsub/<topic>?` (hard-coded host/port) |
| Body parsing | JSON for `application/json` and `application/cloudevents+json`, 100 kB limit |
| Scheduled jobs | two cron jobs `*/15 * * * *` (§7) |

### Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `DATABASE_URI` | none (required) | Mongo URI (compose `mongodb://payment-db:27017`) — replaced by the Postgres settings of PORTING.md |
| `DATABASE_NAME` | none | compose `misarch` — replaced likewise |
| `SIMULATION_URL` | unset | base URL of the provider stub (compose `http://simulation:8080`). Unset → every provider call is skipped with an error log ("Simulation URL not set"). **Keep this name.** |
| `PAYMENT_PROVIDER_URL` | — | set by infrastructure-docker (`http://simulation:8080/payment/register`) but **never read**; port ignores it too |
| `NODE_ENV`, `OTEL_*` | | logging / telemetry as in inventory |

`ConfigModule.forRoot({isGlobal: true})` also reads a `.env` file if present (not used in deployments).

## 2. GraphQL schema (federation v2)

Contract = original `src/payment.gql` (must diff empty against `reference/sdl/payment.graphql`). Summary:

- Scalars: `UUID` (same implementation as inventory: case-insensitive regex check, value returned unchanged,
  `invalid uuid`), `JSONObject` (graphql-type-json; output only; non-object → `JSONObject cannot represent non-object value: <v>`),
  `DateTime` (@nestjs/graphql `GraphQLISODateTime`: output `Date.toISOString()` i.e. `yyyy-MM-ddTHH:mm:ss.SSSZ`;
  input `new Date(string)`, see §4.1).
- `type User @key(fields: "id")` ("Foreign type User"): `id: UUID!`,
  `paymentInformations(skip: Int = 0, first: Int = 2147483647, orderBy: PaymentInformationOrder, filter: PaymentInformationFilter): PaymentInformationConnection` (nullable).
- `type PaymentInformation @key(fields: "id")`: `id: UUID!`, `paymentMethod: PaymentMethod!`,
  `publicMethodDetails: JSONObject`, `user: User!`,
  `payments(skip, first, orderBy: PaymentOrder, filter: PaymentFilter): PaymentConnection` (nullable).
- `type Payment @key(fields: "id")`: `id: UUID!`, `totalAmount: Float!`, `status: PaymentStatus!`,
  `paymentInformation: PaymentInformation!`, `payedAt: DateTime`, `numberOfRetries: Float!`.
- Connections `PaymentConnection`, `PaymentInformationConnection` `{ nodes: [X!], totalCount: Int!, hasNextPage: Boolean! }`.
- Enums: `PaymentMethod { INVOICE PREPAYMENT CREDIT_CARD }`, `PaymentStatus { OPEN PENDING SUCCEEDED FAILED INKASSO }`,
  `PaymentInformationOrderField { ID }`, `PaymentOrderField { ID }`, `OrderDirection { ASC DESC }`.
- Inputs: `PaymentInformationOrder {field, direction}`, `PaymentOrder {field, direction}`,
  `PaymentInformationFilter { user: UUID, paymentMethod: PaymentMethod }`,
  `PaymentFilter { status: PaymentStatus, paymentInformationId: String, paymentMethod: PaymentMethod, from: DateTime, to: DateTime }`,
  `CreateCreditCardInformationInput { cardHolder: String!, cardNumber: String!, expirationDate: String! }`.
- Query: `paymentInformations(...): PaymentInformationConnection!` (description starts with a space:
  `" Retrieves all payment informations matching the filter"`), `payments(...): PaymentConnection!`.
- Mutation: `createCreditCardPaymentInformation(input): PaymentInformation!`, `deletePaymentInformation(id: UUID!): PaymentInformation!`,
  `deletePayment(id: UUID!): Payment!`.

### Federation entity resolution (all public — no `@Roles` on reference resolvers)

- `User`: default (representation is the parent).
- `PaymentInformation`: `findById(id)`; missing → `NotFoundException('Payment Information with id "<id>" not found')`.
- `Payment`: `findById(id)` with populated `paymentInformation`; missing → `NotFoundException('Payment with id "<id>" not found')` (that entity becomes `null`, the others resolve).

### SDL references

`tools/sdl-diff.sh` against `reference/sdl` (live `_service` SDL of the running original): no difference.
Against `reference/schemas` (misarch/schemas, produced by the `graphql-schema-transform` action): that file marks
`PaymentConnection` and `PaymentInformationConnection` `@shareable`, the live service does not; the live SDL wins (PORTING.md §4), the port has no `@shareable`.

## 3. Authorization

`RolesGuard` as in inventory (header `Authorized-User`, `ForbiddenException("Forbidden resource")` when missing,
`JSON.parse`, `roles.includes`), plus: on success it stores the parsed header object as `req.user`.
`@CurrentUser()` = `{id: req.user.id}` (only the id is kept); `@CurrentUserRoles()` = `req.user.roles`.

| Operation | Roles (any) | Additional check in code |
|---|---|---|
| `Mutation.createCreditCardPaymentInformation` | buyer | — (admins without `buyer` are forbidden) |
| `Query.paymentInformations` | admin, employee | — |
| `Mutation.deletePaymentInformation` | admin, employee, buyer | owner check (quirk: never passes for buyers, §4.3) |
| `Query.payments` | admin, employee | — |
| `Mutation.deletePayment` | admin | — |
| `User.paymentInformations` | buyer, admin, employee | not admin/employee and `currentUser.id !== parent.id` → `UnauthorizedException("User not authorized to access foreign Payment Informations}")` (sic, stray `}`) |
| `PaymentInformation.payments` | buyer, admin, employee | not admin/employee and `currentUser.id !== paymentInformation.user.id` → `UnauthorizedException("User not authorized to access foreign Payments}")` |
| `PaymentInformation.user`, `_entities` | public | — |

Field-level denials make the nullable field `null` with an error.

## 4. Resolvers

### 4.1 Common

- Args validation (`ValidationPipe`, also on field resolvers): `skip` `@Min(0)`, `first` `@Min(1)`; failure →
  `Bad Request Exception` (`extensions.originalError.message` = `["skip must not be less than 0", ...]`).
- `CreateCreditCardInformationInput` validation: `cardNumber` must satisfy validator.js `isCreditCard` (sanitize
  `[- ]+`, match one of the provider regexes — including the unanchored mastercard alternative — then Luhn) else
  message `The credit card number is invalid`; `expirationDate` custom validator (below) else
  `The expiration date is invalid or expired`. `cardHolder` unchecked.
- Expiration date validator (quirky, reproduce exactly): `[month, year] = value.split('/').map(v => parseInt(v, 10))`
  (JS `parseInt`: leading whitespace, optional sign, leading digits, otherwise NaN);
  `currentYear = now.year % 100`, `currentMonth = now.month (1-12)` in the server's local time zone (UTC in containers);
  `adjustedYear = year > 2000 ? year - 2000 : year`; invalid iff `adjustedYear < currentYear || adjustedYear > currentYear + 20`
  or (`adjustedYear === currentYear && month < currentMonth`) or `month < 1 || month > 12`. All comparisons with
  NaN/undefined are false, so e.g. `"abc"` or `"12"` is **valid**; non-string → invalid.
  FIXED (P11): the value must match `MM/YY` or `MM/YYYY` (1-2 digit month, whitespace around the parts allowed),
  then the same range checks apply.
- Connection selection keys (payment variant of `queryKeys`): **all** selections directly under the connection field
  of the first field node, mapped to `selection.name.value`: a fragment spread contributes its fragment name, an
  inline fragment throws `TypeError: Cannot read properties of undefined (reading 'value')` (quirk; GraphQL error with
  that message). FIXED (P6): the fields selected through fragment spreads and inline fragments count (graphql-java
  `selectionSet.immediateFields`).
- `buildConnection`: `nodes` only if selected; `totalCount`/`hasNextPage` only if one of them is selected
  (`hasNextPage = skip + first < totalCount`, 64-bit arithmetic in the port).
- Ordering: `orderBy` absent → `_id` ascending. Otherwise `sort({[orderBy.field]: orderBy.direction})` **without
  defaults** (quirk): field missing → sort key `"undefined"` (all documents tie → natural order); direction missing →
  Mongoose throws `TypeError: Invalid sort value: { _id: undefined }` (or `{ undefined: undefined }`), which becomes
  the GraphQL error. Port: same messages; `_id` order = `COLLATE "C"`. FIXED (P8): `field` defaults to `ID`,
  `direction` to `ASC` (no error, no natural-order sort).
- `DateTime` input: JS `new Date(string)` semantics (ISO 8601 with or without time/offset; date-only = UTC midnight,
  date-time without offset = local time = UTC in containers).
  An unparsable value yields an Invalid Date that later fails the Mongo cast with
  `Cast to date failed for value "Invalid Date" (type Date) at path "createdAt" for model "Payment"`.

### 4.2 PaymentInformation

`find(args)` = `find(filter).limit(first).skip(skip).sort(...)`, `count(filter)` = `countDocuments(filter)`, with the
**raw** filter object:
- `filter.paymentMethod` → equality on `paymentMethod` (explicit `null` → matches nothing).
- `filter.user` → equality of the stored `user` sub-document `{id: <userId>}` with the **string** → **never matches**
  (quirk: `Query.paymentInformations(filter:{user: …})` always returns no nodes and `totalCount: 0`).
  FIXED (P7): `filter.user` matches the owner id (`userId`).

| Field | Behaviour |
|---|---|
| `Query.paymentInformations` | connection over all payment informations (§4.1). |
| `User.paymentInformations(args)` | auth check (§3), then connection with `filter = {...args.filter, user: {id: parent.id}}` → matches documents whose `user` is exactly `{id: parent.id}` (i.e. `user_id = parent.id`, case-sensitive); `args.filter.user` is overridden, `args.filter.paymentMethod` applies. |
| `Mutation.createCreditCardPaymentInformation(input)` | creates `{_id: uuidv4(), paymentMethod: CREDIT_CARD, publicMethodDetails: {cardHolder, cardNumber: masked, expirationDate}, secretMethodDetails: input, user: {id: currentUser.id}}` and returns it. Masking: `cardNumber.replace(/\d(?=\d{4})/g, '*')` (only digits followed by 4 more digits; `"4111 1111 1111 1111"` stays unmasked — quirk; FIXED (P10): every digit except the last four is masked, separators kept: `"**** **** **** 1111"`). `secretMethodDetails` keeps the raw input (`{cardHolder, cardNumber, expirationDate}`). No event. |
| `Mutation.deletePaymentInformation(id)` | `findById`; missing → `NotFoundException('Payment Information with id "<id>" not found')`. If the caller is neither admin nor employee: `paymentInfo.user.toString()` is `"[object Object]"` ≠ caller id → **always** `UnauthorizedException('User not authorized to delete Payment Information with id "<id>"')` (quirk: buyers cannot delete their own; FIXED (P7): the stored owner id is compared, buyers delete their own and get the Unauthorized error for foreign ones). Else `findByIdAndDelete` and return the deleted document. Payments referencing it stay. |
| `PaymentInformation.id/paymentMethod/publicMethodDetails` | stored values (`publicMethodDetails` null for INVOICE/PREPAYMENT). |
| `PaymentInformation.user` | `{__typename: "User", id: user.id}`. |
| `PaymentInformation.payments(args)` | auth check, then payment connection with `filter = {...args.filter, paymentInformationId: parent.id}` (§4.3 semantics). |

### 4.3 Payment

`buildQuery(filter)` (used by `find` **and** `count`; truthiness checks, explicit `null` = absent):
- `status` → equality.
- `paymentInformationId` or `paymentMethod` set → `paymentInformation ∈ allowedIds` where
  `allowedIds = paymentMethod ? (ids of ALL payment informations with that method, then: no paymentInformationId → all of them; paymentInformationId ∉ ids → []; else [paymentInformationId]) : [paymentInformationId]`.
- `from` → `createdAt >= from`; `to` → `createdAt <= to` (both may be combined).
`find` = `find(query).limit(first).skip(skip).populate('paymentInformation').sort(...)`.

| Field | Behaviour |
|---|---|
| `Query.payments` | connection (§4.1, §4.3). |
| `Mutation.deletePayment(id)` | `findById` (not populated); missing → `NotFoundException('Payment with id "<id>" not found')`; `findByIdAndDelete`; returns the deleted document **unpopulated**: its `paymentInformation` is the raw id string, so selecting any `paymentInformation` sub-field fails (verified: `paymentInformation{id}` → `Cannot return null for non-nullable field PaymentInformation.id.`, `paymentInformation{user{id}}` → `Cannot read properties of undefined (reading 'id')`) and `data` becomes `null` — after the delete happened (quirk). Without a `paymentInformation` selection it works. The open order is not touched. FIXED (P9): the deleted payment is returned populated. |
| `Payment.id`, `totalAmount`, `status`, `payedAt` | stored values (`payedAt` set only when status becomes SUCCEEDED). |
| `Payment.paymentInformation` | populated document; if the payment information was deleted → populate gives `null` → non-null error. |
| `Payment.numberOfRetries` | **never stored** (no Mongoose default) but the GraphQL field resolver of @nestjs/graphql returns the field's `defaultValue` (`0`) when the property is `undefined` → always `0` (verified). The column stays NULL; the credit-card retry check reads the raw (undefined) value and never reaches 3. FIXED (P2): stored (0 for new payments), incremented per credit-card retry; GraphQL shows the real count (NULL of old rows = 0). |
| `Payment.status` | same mechanism with default `OPEN` (never needed, status is always stored). |

### 4.4 GraphQL error shape (verified)

Same formatting as inventory (spec inventory §9): 400 → `BAD_REQUEST` (+`originalError`), 401 → `UNAUTHENTICATED`
(+`originalError`), 403 → `FORBIDDEN` (+`originalError`), other HttpExceptions (404) → `INTERNAL_SERVER_ERROR` with
`status` and `originalError`, other errors → `INTERNAL_SERVER_ERROR` only. Examples recorded from the original:
`Invalid sort value: { _id: undefined }`, `Invalid sort value: { undefined: undefined }`,
`Cannot read properties of undefined (reading 'value')` (inline fragment), `Cannot return null for non-nullable field PaymentInformationConnection.totalCount.`
(fragment spread), `Cast to date failed for value "Invalid Date" (type Date) at path "createdAt" for model "Payment"`,
`Cannot return null for non-nullable field Payment.paymentInformation.` (payment whose information was deleted).

## 5. Events

### 5.1 `GET /dapr/subscribe` (verbatim)

```json
[
  {"pubsubName":"pubsub","topic":"discount/order/validation-succeeded","route":"order-validation-succeeded"},
  {"pubsubName":"pubsub","topic":"user/user/created","route":"user-created"}
]
```

Handlers are `POST /<route>`, read `body.data`, answer `201` with empty body (Dapr: 2xx success, 404 drop, else
retry). DTO validation failure → `400 {"message":[...],"error":"Bad Request","statusCode":400}` (retry).

### 5.2 Consumed

| Route / topic | Validation | Behaviour |
|---|---|---|
| `order-validation-succeeded` / `discount/order/validation-succeeded` (`{order: OrderDTO}`, published by discount) | `@IsObject() order` only (the nested order is **not** validated) | `startPaymentProcess(order)` **not awaited** → 201 immediately. See §5.4. Missing `data`/`order` → `400 {"message":["order must be an object"],"error":"Bad Request","statusCode":400}`. |
| `user-created` / `user/user/created` (`{id, username, firstName, lastName, dateJoined}`) | `id` IsUUID (any version), `username`, `firstName`, `lastName`, `dateJoined` IsString | **not awaited**: create `{paymentMethod: PREPAYMENT, user: {id}}` then `{paymentMethod: INVOICE, user: {id}}` (sequential, ids uuidv4). No idempotency: every (re)delivery adds two more payment informations. 201. FIXED (P4): one transaction with a per-user advisory lock; a method the user already has is not created again. |

### 5.3 Published (fire-and-forget, errors only logged)

| Topic | When | Payload |
|---|---|---|
| `payment/payment/payment-enabled` | credit card / invoice: right after payment creation; prepayment: on provider callback `SUCCEEDED` | `{"order": <stored order>}` |
| `payment/payment/payment-failed` | start failure (§5.4); cron for overdue invoice/prepayment payments; credit card after ≥3 retries (unreachable, §6.2; FIXED (P2): reachable); FIXED (P5): prepayment provider callback `FAILED` | `{"order": <order>}` |
| `payment/payment/payment-processed` | **never** (`buildPaymentProcessedEvent` unused) | — |

`<stored order>` is the order object received with `validation-succeeded`, kept verbatim (all fields, unknown ones
included) in the open-order record. The port stores it as raw JSON (`json`, not `jsonb`).

### 5.4 Payment start (`startPaymentProcess(order)`)

1. `paymentInformation = findById(order.paymentInformationId)`; missing → NotFound → step 5.
2. Create payment `{_id: uuidv4(), paymentInformation: <info id string>, totalAmount: order.compensatableOrderAmount, status: OPEN, createdAt = updatedAt = now}` (`totalAmount` cast to Number; missing/null/non-numeric → validation error → step 5). The order id is **not** used as payment id.
3. Create open order `{paymentId: payment.id, order}`; failure → step 5.
4. Dispatch by `paymentInformation.paymentMethod`, **not awaited** (errors after this point are not caught):
   - `CREDIT_CARD`: (a) publish `payment-enabled` after loading the open order, (b) `POST ${SIMULATION_URL}/payment/register` `{"paymentId","amount","paymentType":"credit-card"}`, (c) set status `PENDING` — all three started concurrently.
   - `INVOICE`: same with `"paymentType":"invoice"`.
   - `PREPAYMENT`: register with `"paymentType":"prepayment"`, set `PENDING`; **no** `payment-enabled` yet.
   `amount` is `order.compensatableOrderAmount` as received.
5. On error in 1–3: `openOrders.delete(order.id)` (not awaited; it looks up `paymentId == order.id`, never matches and
   rejects → process termination, see D1) and publish `payment-failed {order}` (the publish request goes out first).

FIXED (P1, P4): steps 2 and 3 run in one transaction, the open order (new column `orderId`, unique) first: a second
event for the same order id stores nothing and is ignored (no register, no events). On an error in 1–3 nothing is
stored, `payment-failed {order}` is published and the process keeps running.

## 6. Provider integration

### 6.1 Outgoing (`ConnectorService.send`)

`POST ${SIMULATION_URL}/${endpoint}` with JSON body, axios defaults (no timeout), never awaited by callers; non-2xx
or network errors are logged and swallowed. `SIMULATION_URL` unset → no request.

| Endpoint | Body | Note |
|---|---|---|
| `payment/register` | `{"paymentId":"<uuid>","amount":<number>,"paymentType":"credit-card"\|"invoice"\|"prepayment"}` | simulation validates (`IsUUID`, `IsNumber`, `IsString`), queues and calls back after ~5 s |
| `register` | `{"paymentId":"<uuid>","type":"credit-card"}` | credit-card retry; **wrong path** (simulation answers 404) — quirk; FIXED (P2): the retry is a `payment/register` with `amount` = the stored `totalAmount` |

### 6.2 Incoming `POST /payment/update-payment-status`

Body `{"paymentId": string, "status": PaymentStatus}` (validated: `paymentId must be a string`,
`status must be one of the following values: OPEN, PENDING, SUCCEEDED, FAILED, INKASSO` → 400). The simulation sends
`SUCCEEDED` or `FAILED` and retries (3×, 5 s apart) on any non-2xx answer.

1. `payment = findById(paymentId)` populated; missing → `404 {"message":"Payment with id \"<id>\" not found","error":"Not Found","statusCode":404}`.
   Payment information deleted (populate → null) → TypeError → `500 {"statusCode":500,"message":"Internal server error"}`.
2. By payment method:
   - `INVOICE`: set status (and `payedAt = now` if SUCCEEDED); respond `201` with the updated payment JSON (§6.3).
   - `CREDIT_CARD`: status ≠ FAILED → as invoice. FAILED → reload payment; `numberOfRetries >= 3` (never true:
     the field is never set) → would set FAILED and publish `payment-failed`; otherwise **retry** = `POST ${SIMULATION_URL}/register`
     (404) → respond `201` with empty body. Status stays `PENDING`, nothing else happens (quirk: failed credit-card
     payments hang forever, no `payment-failed`). FIXED (P2): below 3 retries `numberOfRetries` is incremented
     (atomically) and the payment is registered again (empty 201, status stays PENDING); the 4th FAILED sets FAILED
     (once) and publishes `payment-failed`; the answer is the payment JSON.
   - `PREPAYMENT`: status ≠ SUCCEEDED → set status, respond with JSON. SUCCEEDED → publish `payment-enabled` (open
     order lookup, not awaited) and respond `201` empty; **the status is not updated** (stays `PENDING`, no `payedAt`) —
     quirk with a delayed effect, see §7. FIXED (P3): SUCCEEDED is stored with `payedAt`; `payment-enabled` is
     published only on the transition (a repeated callback does not publish again); the answer is the payment JSON.
     FIXED (P5): FAILED is stored and publishes `payment-failed` (on the transition).

### 6.3 Response JSON of a status update (verified)

```json
{"_id":"df009142-…","totalAmount":1999,"status":"SUCCEEDED",
 "paymentInformation":{"_id":"2551b5cc-…","paymentMethod":"INVOICE","user":{"id":"9af29af9-…"},"id":"2551b5cc-…"},
 "createdAt":"2026-10-13T12:17:12.073Z","updatedAt":"2026-10-13T12:17:14.159Z","payedAt":"2026-10-13T12:17:14.159Z",
 "id":"df009142-…"}
```
A credit-card information additionally carries `publicMethodDetails` and `secretMethodDetails` (raw card data —
leaked, quirk; FIXED (P10): `secretMethodDetails` is left out) between `paymentMethod` and `user` (Mongo field order of the document). Dates are ISO strings with
milliseconds. The only caller (simulation) ignores the body. Empty-body cases (credit-card FAILED, prepayment
SUCCEEDED) answer `201` with `Content-Length: 0`.

### 6.4 Outgoing request details (verified)

`POST http://<SIMULATION_URL>/payment/register`, headers `Content-Type: application/json`, `Accept: application/json, text/plain, */*`,
body `{"paymentId":"<uuid>","amount":1999,"paymentType":"credit-card"}` (key order paymentId, amount, paymentType).
Retry: `POST <SIMULATION_URL>/register` body `{"paymentId":"<uuid>","type":"credit-card"}` → 404 (FIXED (P2): see §6.1).
Publish: `POST http://127.0.0.1:3500/v1.0/publish/pubsub/payment/payment/payment-enabled?` (also `payment-failed`),
`Content-Type: application/json`, body `{"order":<stored order verbatim>}`.

## 7. Scheduled jobs (`@Cron('*/15 * * * *')`, minute 0/15/30/45, server time zone)

| Job | Selects | Action per payment |
|---|---|---|
| invoice | `status = PENDING`, method INVOICE (via allowed ids), `createdAt <= now − 30 days` (calendar days) | set `FAILED` (not awaited) and publish `payment-failed {order}` from the open order (not awaited) |
| prepayment | same with PREPAYMENT and 7 days | same |

**Quirk with real impact:** prepayments that *succeeded* stay `PENDING` (§6.2), so 7 days later the job marks them
FAILED and publishes `payment-failed`, and inventory then releases the already shipped/delivered items back to
`IN_STORAGE`. FIXED (P3): succeeded prepayments are stored as SUCCEEDED and no longer selected. Each replica runs
the jobs independently (no leader election).

## 8. Persistence (Mongo → Postgres)

| Mongo collection | Document | Postgres table |
|---|---|---|
| `paymentinformations` | `{_id: uuid string, paymentMethod, publicMethodDetails?: object, secretMethodDetails?: object, user: {id}}` (Mixed) | `PaymentInformationEntity(id TEXT COLLATE "C" PK, paymentMethod TEXT, publicMethodDetails JSON, secretMethodDetails JSON, userId TEXT, seq BIGINT IDENTITY)` |
| `payments` | `{_id: uuid string, totalAmount: number, status, paymentInformation: <info id string> (Mixed with ref), payedAt?: Date, createdAt, updatedAt}`; `numberOfRetries` never present | `PaymentEntity(id TEXT COLLATE "C" PK, totalAmount DOUBLE PRECISION, status TEXT, paymentInformation TEXT, payedAt TIMESTAMPTZ, numberOfRetries DOUBLE PRECISION (always NULL), createdAt, updatedAt TIMESTAMPTZ, seq BIGINT IDENTITY)` |
| `openorders` | `{_id: ObjectId, paymentId, order: <raw order>, __v}` | `OpenOrderEntity(id BIGINT IDENTITY PK, paymentId TEXT, orderJson JSON, orderId TEXT UNIQUE (V2, fix P4))` |

- Ids are text (case-sensitive string semantics of Mongo; ordering `COLLATE "C"`).
- `json` (not `jsonb`) keeps the received JSON text; timestamps are truncated to milliseconds (JS `Date`).
- `updated_at` is refreshed by every status update (Mongoose timestamps).
- Open orders are never deleted in practice (only by the unused processed path), so the table grows forever (as in the original).
- `V2__fixes.sql`: `numberOfRetries` default 0 (NULLs set to 0), `OpenOrderEntity.orderId` + unique index.
- Indexes on `PaymentEntity(paymentInformation)`, `(status)`, `(createdAt)`, `PaymentInformationEntity(userId)`,
  `(paymentMethod)`, `OpenOrderEntity(paymentId)`. Migration `services/payment/src/main/resources/db/migration/V1__init.sql`.
- The stored `user` sub-document `{id}` becomes the column `userId`; filters keep Mongo's semantics (`user` compared
  with a string never matches — FIXED (P7); `User.paymentInformations` matches `userId`).
- `sort({undefined: ±1})` (natural order) = `ORDER BY seq` (no longer used since P8; `seq` stays).

## 9. Quirks (reproduce) and deviations

Reproduced by the faithful port; the defect fixes (docs/fixes/payment.md) changed the marked ones:
1. `user` filter of `Query.paymentInformations` never matches; buyers can never delete their own payment information. FIXED (P7)
2. Card mask only masks contiguous digit runs; secret card data kept and leaked in the callback response. FIXED (P10; the secret details are still stored)
3. Expiration validator accepts garbage like `"abc"`; FIXED (P11). Credit card regex quirks of validator.js: kept.
4. `numberOfRetries` never stored (GraphQL shows 0); the credit-card retry limit can never trigger. FIXED (P2)
5. `orderBy` without defaults → sort errors / no-op sort. FIXED (P8)
6. Inline fragments under connections → TypeError. FIXED (P6, also fragment spreads)
7. Credit-card FAILED → retry to wrong URL, payment stays PENDING, no failure event. FIXED (P2)
8. Prepayment SUCCEEDED → status not stored; the 7-day cron later fails it and triggers inventory release. FIXED (P3); prepayment FAILED published nothing: FIXED (P5)
9. `payment-processed` never published; open orders never deleted. Not fixed (docs/fixes/payment.md)
10. `deletePayment` returns an unpopulated payment → sub-selection errors after a successful delete. FIXED (P9)
11. No idempotency (`user-created` duplicates payment informations; redelivered `validation-succeeded` creates a second payment and registers it again). FIXED (P4)
12. All work after the HTTP response (event handlers, provider calls, publishes) is fire-and-forget. Not fixed (convention)

Deviations:
- D1 (reproduced, switchable) Unhandled promise rejections terminate the original process. Verified: an
  `order-validation-succeeded` whose `paymentInformationId` is unknown answers 201, publishes `payment-failed`, and then
  the process exits because the always-failing `openOrders.delete(order.id)` rejects (`NotFoundException: Open order for payment: <orderId> not found`);
  Docker restarts it. Any start failure (unknown payment information, invalid amount) therefore restarts payment.
  Other sources: failing `addDefaultPaymentInformations`, missing open order when publishing enabled/failed events,
  failing fire-and-forget status updates. The port publishes `payment-failed`, logs and terminates the JVM (`halt(1)`);
  `MISARCH_EXIT_ON_UNHANDLED_ERROR=false` only logs. Restart time differs (JVM vs Node).
  FIXED (P1): a start failure no longer terminates the process (nothing is deleted; nothing was stored). The
  mechanism stays for real unexpected failures of fire-and-forget work (e.g. database errors).
- D2 Exact texts of database errors and `extensions.stacktrace` are not reproduced; GraphQL parse/validation-phase
  errors use graphql-java texts and HTTP 200 (Apollo: 400 for invalid literals); `data: null` is always written.
- D3 Float output: reproduced — a service-local `Float` coercing writes integral values without fraction (`1999`).
- D4 `DateTime` input: ECMAScript ISO date-time formats (plus a space separator and `±HHmm`) are parsed like V8;
  V8's legacy fallback formats (`Jan 1 2024`, `2024/01/01`) are Invalid Date in the port.
- D5 Provider HTTP client: Spring WebClient (connection keep-alive, headers `Content-Type`, `Accept`) instead of
  axios (`Connection: close`, `User-Agent: axios/1.6.8`). Same requests and bodies.
- D6 Cron: Spring `@Scheduled(cron = "0 */15 * * * *")` in the JVM default time zone (container: UTC), same as
  `@Cron('*/15 * * * *')`.

## 10. Test plan (equivalence)

(Since the defect fixes, the tests assert the fixed behaviour for the items above; docs/fixes/payment.md lists them.)

Unit:
- Validation corpus: every case of `reference/payment-validation-corpus.json` gives the identical OK / message array (clock fixed to the generation time).
- Expiration validator table (valid current/next months, past month this year, `MM/YYYY`, year +21, month 0/13, `"abc"`, `"12"`, `" 5/30"`, non-string), fixed clock.
- isCreditCard: valid Visa/Amex/Mastercard 2-series/Discover numbers with spaces and dashes, Luhn failure, letters, mastercard prefix quirk.
- Mask: contiguous 16 digits, spaced, short (≤4 digits) strings.
- Role guard and owner checks; selection keys with fragment spread / inline fragment (TypeError text).
- DateTime parsing (date-only, offset, invalid) and ISO output with milliseconds.

Integration (Testcontainers Postgres, GraphQL over HTTP, fake Dapr + fake simulation HTTP server):
- `createCreditCardPaymentInformation` as buyer: stored public/secret details, masking, returned JSONObject; as admin without buyer → Forbidden; invalid card/expiry → `Bad Request Exception` with messages.
- `paymentInformations` (admin): ordering ASC/DESC, field without direction → `Invalid sort value` error, skip/first, `filter.user` → empty + totalCount 0, `filter.paymentMethod`.
- `_entities(User){paymentInformations}`: own (buyer) ok, foreign → Unauthorized text, admin sees foreign; filter by method.
- `deletePaymentInformation`: buyer own → Unauthorized; admin → deleted; missing → NotFound.
- `payments` filters: status, paymentInformationId, paymentMethod with/without matching id, from/to window (date-only, offset, `garbage` → cast error text), count consistency; `numberOfRetries` = 0; `PaymentInformation.payments` owner check.
- `deletePayment`: admin, missing → NotFound, sub-selection on paymentInformation → error after delete.
- `_entities` Payment / PaymentInformation public, not-found texts.
Event & provider flows:
- `/dapr/subscribe` verbatim.
- user-created → two payment informations (PREPAYMENT, INVOICE) for the user; invalid DTO → 400; duplicate → four.
- validation-succeeded for each method: payment row (OPEN→PENDING), open order with verbatim order JSON, register call body at the fake simulation, `payment-enabled` published for credit card and invoice only, payload `{order}` unchanged; unknown payment information → `payment-failed` published, no payment row, terminator invoked.
- update-payment-status: invoice SUCCEEDED (payedAt set, JSON body, 201), credit card FAILED (call to `/register`, status stays PENDING, empty 201), prepayment SUCCEEDED (payment-enabled published, status unchanged), prepayment FAILED (status FAILED), unknown id → 404 body, invalid status → 400 body, deleted payment information → 500.
- Cron jobs invoked directly with a fixed clock: overdue invoice (31 d) / prepayment (8 d) PENDING → FAILED + payment-failed; younger or non-PENDING untouched; succeeded-but-PENDING prepayment is failed (quirk).
- `_service { sdl }` diff empty; `GET /health`.

## 11. Evidence

Verified on 2026-10-13 against `ghcr.io/misarch/payment:main` (Node 18.20.8, mongoose 8.2.0, @nestjs/mongoose 10.0.4,
@nestjs/graphql 12.1.1, @nestjs/apollo 12.1.0, class-validator 0.14.1, validator 13.11.0) with MongoDB 6.0, a fake Dapr
sidecar and a fake simulation in an isolated network:
- `@Prop({type: Types.ObjectId, ref})` and `@Prop() user: User` are inspected by @nestjs/mongoose into `{type: {}}` =
  `Mixed`, so UUID strings are stored and `populate` works through `ref`; a missing reference populates as `null`.
- `findOneAndUpdate(..., {overwrite: true, new: true})` is a plain `$set` (Mongoose 8).
- `sort({_id: undefined})` throws `Invalid sort value: { _id: undefined }`; `sort({undefined: 1})` = natural order.
- `{user: "<id>"}` never matches the stored `{id}` sub-document; `paymentInfo.user.toString()` is `[object Object]`.
- @nestjs/graphql's default field resolver returns the `@Field({defaultValue})` for `undefined` properties.
- `GraphQLISODateTime`: `parseValue/parseLiteral` = `new Date(string)`, `serialize` = `toISOString()`.
- Recorded GraphQL responses, REST bodies/status codes, outgoing register/retry requests, publishes and the crash.
- Validation corpus (125 inputs → exact `message` arrays, generated with `TZ=UTC` at 2026-10-13T12:19Z from the original
  DTO classes and `ValidationPipe`): test fixture of the port (`src/test/resources/reference/`), run with a fixed clock.
