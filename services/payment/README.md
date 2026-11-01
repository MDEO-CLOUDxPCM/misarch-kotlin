# payment

Payment informations of users (prepayment, invoice, credit cards) and the payment of every validated order: it
registers the payment with the external payment provider (the `simulation` service), applies the provider's status
callbacks (credit cards are retried three times) and fails overdue invoice and prepayment payments every 15 minutes.
Original: `misarch/payment` (TypeScript, NestJS, MongoDB). Spec and deviations:
[docs/specs/payment.md](../../docs/specs/payment.md), fixed defects: [docs/fixes/payment.md](../../docs/fixes/payment.md).

| Contract | Value |
|---|---|
| Dapr app id | `payment` |
| HTTP | port 8080: GraphQL `POST /graphql`, `GET /health`, `GET /dapr/subscribe`; provider callback `POST /payment/update-payment-status` (`{"paymentId","status"}`) |
| subscriptions | `discount/order/validation-succeeded`, `user/user/created`, each at `POST /subscription/<topic>` |
| publishes | `payment/payment/payment-enabled`, `payment/payment/payment-failed` (`{"order": <order as received>}`) |
| invokes | the payment provider: direct HTTP `POST ${SIMULATION_URL}/payment/register` (`{"paymentId","amount","paymentType"}`, also for credit card retries) |
| DB | PostgreSQL, tables `PaymentInformationEntity`, `PaymentEntity`, `OpenOrderEntity` (the order of each payment) |
| env | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, `OTEL_*`, `DAPR_HTTP_PORT` / `DAPR_HTTP_ENDPOINT`, `SIMULATION_URL` (provider base URL, compose `http://simulation:8080`; unset: payments are not registered) |

## Layout

```
PaymentApplication.kt       Spring Boot entry point
event/                      PaymentEvents (topics), EventController (subscriptions), model/ (event DTOs)
graphql/                    Query, Mutation, PaymentScalarConfiguration, model/ (Payment, PaymentInformation,
                            PaymentMethod, PaymentStatus, User, connection/), input/, dataloader/, federation/
persistence/                model/ (entities), repository/
provider/                   PaymentProviderClient (registration), PaymentProviderController (status callback),
                            configuration properties, the payment definition sent to the provider
service/                    PaymentService, PaymentRegistrationService (stores, then registers outside the
                            transaction), PaymentInformationService, CreditCardValidator
```

## Build and test

```
tools/build.sh :services:payment:test
tools/sdl-diff.sh payment                 # empty
tools/image.sh payment                    # misarch-kotlin/payment:dev
```

`tools/sdl-diff.sh payment --schemas` compares against the `misarch/schemas` copy that the gateway composes; it
differs only by the transform-added `@shareable` on `PaymentConnection` and `PaymentInformationConnection`.
