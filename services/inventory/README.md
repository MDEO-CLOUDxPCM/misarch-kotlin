# inventory

Product items in stock per product variant: creation, reservation for orders and the status of reserved items along
the buy saga (payment, shipment). Spring Boot 4, graphql-kotlin, R2DBC + Flyway on Postgres, like the other
services. Originally `misarch/inventory` (TypeScript/NestJS/MongoDB); behaviour of the original, fixes and
deviations: [docs/specs/inventory.md](../../docs/specs/inventory.md), [docs/fixes/inventory.md](../../docs/fixes/inventory.md).

Contract: Dapr app-id `inventory`, port 8080, `POST /graphql` (federation SDL identical to
`reference/sdl/inventory.graphql`), `GET /health`. Subscribes to `catalog/product-variant/created`,
`order/order/created`, `payment/payment/payment-enabled|payment-failed`, `shipment/shipment/created|status-updated`,
`discount/order/validation-failed` (routes `/subscription/<topic>`); publishes
`inventory/product-item/reservation-succeeded|failed` with `{"order": <order as received>[, failedProductVariantIds]}`.
order's synchronous `_entities(ProductVariant){inventoryCount}` call works without `Authorized-User`.

## Configuration

| Env var | Meaning |
|---|---|
| `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD` | Postgres |
| `DAPR_HTTP_PORT` / `DAPR_HTTP_ENDPOINT` | Dapr sidecar (default `http://localhost:3500`) |
| `OTEL_*` | OpenTelemetry Java agent (image) |

## Layout

`persistence/` (entities `ProductItemEntity`, `ProductVariantEntity`; repositories), `service/` (product items,
reservation of orders, product variants), `graphql/` (Query, Mutation, types, connection, data loader, entity
resolvers), `event/` (`EventController`, topics, event DTOs).

## Tests

`tools/build.sh :services:inventory:test`: SDL comparison, integration tests (Testcontainers Postgres, fake Dapr
sidecar) for every query, mutation, entity, subscription and published event, and a migration test for V3.
