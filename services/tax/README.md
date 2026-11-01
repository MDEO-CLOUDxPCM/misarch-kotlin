# tax

Tax rates and their immutable versions (employee-only API). It publishes `tax/tax-rate/created` (consumed by
catalog) and `tax/tax-rate-version/created` (consumed by order), and consumes no events. Original: `misarch/tax`
(Kotlin, Spring Boot 3.2, graphql-kotlin 7, R2DBC + QueryDSL). Spec and deviations:
[docs/specs/tax.md](../../docs/specs/tax.md), fixed defects: [docs/fixes/tax.md](../../docs/fixes/tax.md).

| Contract | Value |
|---|---|
| Dapr app id | `tax` |
| HTTP | port 8080: GraphQL `POST /graphql`, `GET /health`, `GET /dapr/subscribe` |
| subscriptions | none |
| publishes | `tax/tax-rate/created`, `tax/tax-rate-version/created` |
| DB | PostgreSQL, tables `TaxRateEntity`, `TaxRateVersionEntity` |
| env | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, `OTEL_*`, `DAPR_HTTP_PORT` / `DAPR_HTTP_ENDPOINT` |

## Layout

```
TaxApplication.kt           Spring Boot entry point
event/                      TaxEvents (topics), model/ (event DTOs)
graphql/                    Query, Mutation, model/ (TaxRate, TaxRateVersion, connection/), input/, dataloader/,
                            federation/
persistence/                model/ (entities), repository/
service/                    TaxRateService, TaxRateVersionService
```

## Build and test

```
tools/build.sh :services:tax:test
tools/sdl-diff.sh tax                     # empty
tools/image.sh tax                        # misarch-kotlin/tax:dev
```
