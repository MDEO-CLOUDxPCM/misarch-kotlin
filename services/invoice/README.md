# invoice

Invoices of validated orders: on `discount/order/validation-succeeded` it renders the invoice from its replicas of
users, user addresses and vendor addresses, stores it, publishes `invoice/invoice/created` and contributes
`Order.invoice` to the federated graph. Original: `misarch/invoice` (Rust, axum, async-graphql, MongoDB). Spec and
deviations: [docs/specs/invoice.md](../../docs/specs/invoice.md), fixed defects:
[docs/fixes/invoice.md](../../docs/fixes/invoice.md).

| Contract | Value |
|---|---|
| Dapr app id | `invoice` |
| HTTP | port 8080: GraphQL `POST /` (gateway: `/v1.0/invoke/invoice/method/`), `GET /health`, `GET /dapr/subscribe` |
| subscriptions | `discount/order/validation-succeeded`, `user/user/created`, `address/user-address/created`, `address/user-address/archived`, `address/vendor-address/created`, each at `POST /subscription/<topic>` |
| publishes | `invoice/invoice/created` |
| DB | PostgreSQL, tables `InvoiceEntity`; replicas `UserEntity`, `UserAddressEntity`, `VendorAddressEntity` |
| env | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, `OTEL_*`, `DAPR_HTTP_PORT` / `DAPR_HTTP_ENDPOINT` |

## Layout

```
InvoiceApplication.kt       Spring Boot entry point
event/                      InvoiceEvents (topics), EventController (subscriptions), model/ (event DTOs)
graphql/                    Query, model/ (Invoice, Order, UserAddress, VendorAddress), dataloader/ (by id, and by
                            order id for Order.invoice), federation/
persistence/                model/ (entities), repository/
service/                    InvoiceService (rendering, insert and publish in one transaction), UserService,
                            UserAddressService, VendorAddressService
```

## Build and test

```
tools/build.sh :services:invoice:test
tools/sdl-diff.sh invoice                 # empty
tools/image.sh invoice                    # misarch-kotlin/invoice:dev
```
