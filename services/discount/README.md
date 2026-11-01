# discount

Discounts (relative price reductions on product variants, products or categories), coupons, coupon redemptions and
per-user discount usages. `order` calls it during `createOrder` (`findApplicableDiscounts`); in the buy saga it
validates the per-user usage limits on `inventory/product-item/reservation-succeeded`. Original: `misarch/discount`
(Kotlin, Spring Boot 3.2, graphql-kotlin 7, R2DBC + QueryDSL). Spec and deviations:
[docs/specs/discount.md](../../docs/specs/discount.md), fixed defects: [docs/fixes/discount.md](../../docs/fixes/discount.md).

| Contract | Value |
|---|---|
| Dapr app id | `discount` |
| HTTP | port 8080: GraphQL `POST /graphql`, `GET /health`, `GET /dapr/subscribe` |
| subscriptions | `user/user/created`, `catalog/product/created`, `catalog/category/created`, `catalog/product-variant/created`, `inventory/product-item/reservation-succeeded`, each at `POST /subscription/<topic>` |
| publishes | `discount/discount/created`, `discount/discount/updated`, `discount/coupon/created`, `discount/coupon/updated`, `discount/order/validation-succeeded`, `discount/order/validation-failed` |
| DB | PostgreSQL, tables `DiscountEntity`, `DiscountToProductVariantEntity`, `DiscountToProductEntity`, `DiscountToCategoryEntity`, `CouponEntity`, `CouponRedemptionEntity`, `DiscountUsageEntity`; replicas `UserEntity`, `ProductEntity`, `ProductVariantEntity`, `CategoryEntity`, `ProductToCategoryEntity` |
| env | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, `OTEL_*`, `DAPR_HTTP_PORT` / `DAPR_HTTP_ENDPOINT` |

## Layout

```
DiscountApplication.kt      Spring Boot entry point
event/                      DiscountEvents (topics), EventController (subscriptions), model/ (event DTOs,
                            model/order/: the order DTOs of the saga)
graphql/                    Query, Mutation, model/ (Discount, Coupon, DiscountUsage, DiscountsForProductVariant,
                            User, Product, ProductVariant, Category, connection/), input/, dataloader/, federation/
persistence/                model/ (entities), repository/
service/                    DiscountService, CouponService, DiscountUsageService, UserService, ProductService,
                            ProductVariantService, CategoryService
```

## Build and test

```
tools/build.sh :services:discount:test
tools/sdl-diff.sh discount                # empty
tools/image.sh discount                   # misarch-kotlin/discount:dev
```

`tools/sdl-diff.sh discount --schemas` compares against the older `misarch/schemas` copy that the gateway composes;
it differs only in `@shareable` on `DiscountsForProductVariant`.
