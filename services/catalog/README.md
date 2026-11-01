# catalog

Categories with their characteristics and values, products, product variants and product variant versions. It
replicates tax rates and media (id only). Original: `misarch/catalog` (Kotlin, Spring Boot 3.2, graphql-kotlin 7,
R2DBC + QueryDSL). Spec and deviations: [docs/specs/catalog.md](../../docs/specs/catalog.md), fixed defects:
[docs/fixes/catalog.md](../../docs/fixes/catalog.md).

| Contract | Value |
|---|---|
| Dapr app id | `catalog` |
| HTTP | port 8080: GraphQL `POST /graphql`, `GET /health`, `GET /dapr/subscribe` |
| subscriptions | `tax/tax-rate/created`, `media/media/created`, each at `POST /subscription/<topic>` |
| publishes | `catalog/category/created`, `catalog/product/created`, `catalog/product/updated`, `catalog/product-variant/created`, `catalog/product-variant/updated`, `catalog/product-variant-version/created` |
| DB | PostgreSQL, tables `CategoryEntity`, `CategoryCharacteristicEntity`, `CategoryCharacteristicValueEntity`, `ProductEntity`, `ProductToCategoryEntity`, `ProductVariantEntity`, `ProductVariantVersionEntity`, `ProductVariantVersionToMediaEntity`; replicas `TaxRateEntity`, `MediaEntity` |
| env | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, `OTEL_*`, `DAPR_HTTP_PORT` / `DAPR_HTTP_ENDPOINT` |

## Layout

```
CatalogApplication.kt       Spring Boot entry point
event/                      CatalogEvents (topics), EventController (subscriptions), model/ (event DTOs)
graphql/                    Query, Mutation, model/ (Category, Categorical/NumericalCategoryCharacteristic and their
                            values, Product, ProductVariant, ProductVariantVersion, TaxRate, Media, connection/),
                            input/, dataloader/, federation/
persistence/                model/ (entities), repository/
service/                    CategoryService, CategoryCharacteristicService, CategoryCharacteristicValueService,
                            ProductService, ProductVariantService, ProductVariantVersionService, TaxRateService,
                            MediaService
```

## Build and test

```
tools/build.sh :services:catalog:test
tools/sdl-diff.sh catalog                 # empty
tools/image.sh catalog                    # misarch-kotlin/catalog:dev
```
