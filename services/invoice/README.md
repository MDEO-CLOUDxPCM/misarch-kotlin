# invoice (Kotlin port)

Drop-in replacement of MiSArch's `invoice` service. It renders the invoice of every validated order
(`discount/order/validation-succeeded`) from its replicas of users, user addresses and vendor addresses, stores it,
publishes `invoice/invoice/created` and contributes `Order.invoice` to the federated graph.

Original: `misarch/invoice` (Rust, axum, async-graphql, MongoDB), which never created an invoice (defects D1-D6).
The behaviour of the original, the fixes and every visible deviation are in
[docs/specs/invoice.md](../../docs/specs/invoice.md) and [docs/fixes/invoice.md](../../docs/fixes/invoice.md).

| Contract | Value |
|---|---|
| Dapr app id | `invoice` |
| HTTP | port 8080: GraphQL `POST /` (gateway: `/v1.0/invoke/invoice/method/`), `GET /health`, `GET /dapr/subscribe`, `GET /dapr/config`, `GET /healthz` |
| subscriptions | `discount/order/validation-succeeded`, `address/vendor-address/created`, `user/user/created`, `address/user-address/created`, `address/user-address/archived`. Each one is served at `POST /subscription/<topic>` |
| publishes | `invoice/invoice/created` (`{"order", "invoice": {"orderId", "issuedAt", "content"}}`) |
| DB | PostgreSQL, tables `InvoiceEntity`, `UserEntity`, `UserAddressEntity`, `VendorAddressEntity` |
| env | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, `OTEL_*` (set `OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318`), `DAPR_HTTP_PORT` / `DAPR_HTTP_ENDPOINT` |

## Structure

* `event/`: topics, the `@Topic` controller and the event DTOs.
* `graphql/`: `Query`, `model/`, `dataloader/` (by id, and by order id for `Order.invoice`), `federation/`, the scalars
  without descriptions.
* `persistence/`: entities and `CoroutineCrudRepository`s.
* `service/`: `InvoiceService` (rendering, insert and publish in one transaction) and the replica services.

## Build and test

```
tools/build.sh :services:invoice:test     # integration (Testcontainers Postgres + fake Dapr), migration, SDL
tools/sdl-diff.sh invoice                 # empty
```
