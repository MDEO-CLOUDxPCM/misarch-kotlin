# discount (Kotlin port)

Drop-in replacement of MiSArch's `discount` service. It manages discounts (relative price reductions on product
variants, products or categories), coupons, coupon redemptions and per-user discount usages. `order` calls it
synchronously during `createOrder` (`findApplicableDiscounts`). In the buy saga it validates the per-user usage limits
on `inventory/product-item/reservation-succeeded`.

Original: `misarch/discount` (Kotlin, Spring Boot 3.2, graphql-kotlin 7, R2DBC + QueryDSL, Dapr SDK 1.11).
The behavioural spec, quirks (preserved defects Q1–Q9) and deviations are in
[docs/specs/discount.md](../../docs/specs/discount.md).

| Contract | Value |
|---|---|
| Dapr app id | `discount` |
| HTTP | port 8080: `POST /graphql` (gateway; order via `/v1.0/invoke/discount/method/graphql`), `GET /health`, `GET /dapr/subscribe`, `GET /dapr/config`, `GET /healthz` |
| subscriptions | `user/user/created`, `catalog/product/created`, `catalog/category/created`, `catalog/product-variant/created`, `inventory/product-item/reservation-succeeded`. Each one is served at `POST /subscription/<topic>` |
| publishes | `discount/discount/created`, `discount/discount/updated`, `discount/coupon/created`, `discount/coupon/updated`, `discount/order/validation-succeeded`, `discount/order/validation-failed`. Payloads are identical to the original: null fields are omitted, and timestamps use `ISO_OFFSET_DATE_TIME` |
| DB | PostgreSQL. The original Flyway migration `V1_1__create_relations.sql` is copied byte for byte (checksum 247510061, triggers included), so an existing original DB validates and is reused as is |
| env | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, `OTEL_*`, `DAPR_HTTP_PORT` / `DAPR_HTTP_ENDPOINT` |

## Structure

The structure is the same as the original:
* `event/`: topics, the `@Topic` controller and the event DTOs (`model/order/`: the order DTOs of the saga).
* `graphql/`: `Query`, `Mutation`, `model/`, `model/connection/` (`CommonOrder` comes from libs/common), `input/`, `dataloader/`, `federation/`.
* `persistence/`: entities and `CoroutineCrudRepository`s. The native SQL is kept verbatim, including the defective
  `upsertDiscountUsage`.
* `service/`.

QueryDSL was replaced by `libs/common`'s `BaseConnection`/`Condition`. QueryDSL joins became `INNER JOIN ... j`
clauses. The applicability conditions (variant/product/category sub-selects, the coupon conditions, the validity
window, the min order amount) are `Condition` fragments with the same SQL shape. `findApplicableDiscounts` runs its
per-variant query (no ORDER BY) through libs/common `ConditionQueries`. The link tables have composite primary keys
(embedded `@Id` key class, `Persistable`); transactions are on the service methods.

## Build and test

```
tools/build.sh :services:discount:test     # integration, saga (Testcontainers Postgres + fake Dapr), migration, SDL and unit tests
tools/sdl-diff.sh discount                 # empty (--schemas: only `@shareable` on DiscountsForProductVariant, see spec)
tools/image.sh discount                    # misarch-kotlin/discount:dev
```

The saga tests apply the calibration workaround for defect Q1
(`architecture-optimization/cases/misarch/timing/calibration/workarounds/discount_usage_columns.sql`) to cover the success path, and remove it
afterwards. All test JVMs run with `-Duser.timezone=UTC` (build-logic), like the containers.
