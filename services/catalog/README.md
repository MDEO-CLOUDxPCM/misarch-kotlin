# catalog (Kotlin port)

Drop-in replacement of MiSArch's `catalog` service: categories with characteristics and values, products, product
variants and product variant versions. It also replicates tax rates and media (id only).
Original: `misarch/catalog` (Kotlin, Spring Boot 3.2, graphql-kotlin 7, R2DBC + QueryDSL, Dapr SDK 1.11).
The behavioural spec, quirks and deviations are in [docs/specs/catalog.md](../../docs/specs/catalog.md).

| Contract | Value |
|---|---|
| Dapr app id | `catalog` |
| HTTP | port 8080: `POST /graphql`, `GET /health`, `GET /dapr/subscribe`, `GET /dapr/config`, `GET /healthz` |
| subscriptions | `tax/tax-rate/created` → `POST /subscription/tax/tax-rate/created`, `media/media/created` → `POST /subscription/media/media/created` |
| publishes | `catalog/category/created`, `catalog/product/created`, `catalog/product/updated`, `catalog/product-variant/created`, `catalog/product-variant/updated`, `catalog/product-variant-version/created` (payloads identical to the original, including `"publiclyVisible"`) |
| DB | PostgreSQL. The original's Flyway migrations are copied unchanged (`V1_1`–`V1_5`). |
| env | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, `OTEL_*`, `DAPR_HTTP_PORT` / `DAPR_HTTP_ENDPOINT` |

## Structure

The structure is the same as the original:
* `event/`: topics, the `@Topic` controller and the event DTOs.
* `graphql/`: `Query`, `Mutation`, `model/`, `model/connection/`, `input/`, `dataloader/`, `federation/`.
* `persistence/`: entities and `CoroutineCrudRepository`s.
* `service/`.

QueryDSL was replaced by `libs/common`'s `BaseConnection`/`Condition`; the joins became SQL `INNER JOIN` clauses.
`CategoricalCategoryCharacteristicValueConnection` (distinct values) runs its own SQL through `DatabaseClient`.

## Build and test

```
tools/build.sh :services:catalog:test     # 22 tests: 16 integration (Testcontainers Postgres + fake Dapr), 1 SDL, 5 unit
tools/sdl-diff.sh catalog                 # empty (also with --schemas)
tools/image.sh catalog                    # misarch-kotlin/catalog:dev
```
