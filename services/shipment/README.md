# shipment (Kotlin port)

Port of [misarch/shipment](https://github.com/MiSArch/shipment) (Kotlin 1.9 / Spring Boot 3.2 / graphql-kotlin 7 /
QueryDSL) onto the uniform stack (see the root README). Behavioural spec, quirks and deviations:
[docs/specs/shipment.md](../../docs/specs/shipment.md).

Owns shipment methods and shipments. It keeps replicas of addresses, product-variant weights and order items, and it
computes shipping fees for `order` (`calculateShipmentFees`, a synchronous Dapr invocation). On `payment-enabled` it
creates one shipment per shipment method and registers it with the external provider (`simulation`, direct HTTP,
retries via experiment-config). It publishes `shipment/*` events and accepts the provider's status callbacks.

| | |
|---|---|
| Port / health | 8080, `GET /health` |
| GraphQL | `POST /graphql` (subgraph `shipment`, federation v2.5), `GET /sdl` |
| Dapr app id | `shipment`; subscriptions `/subscription/<topic>` for `address/user-address/created`, `address/vendor-address/created`, `catalog/product-variant-version/created`, `payment/payment/payment-enabled`, `return/return/created` |
| Publishes | `shipment/shipment/{created,creation-failed,status-updated}`, `shipment/shipment-method/{created,archived}` |
| REST | `POST /shipment/{id}/status` (provider callback), `GET /ecs/defined-variables`, `POST /ecs/variables` |
| ECS variables | `providerRetries` (3), `providerRetryDelay` (500 ms) |
| Env | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, **`MISARCH_SHIPMENT_PROVIDER_ENDPOINT` (required)**, `DAPR_HTTP_PORT`, `OTEL_*` |
| DB | Postgres, original Flyway migrations `V1_1`..`V1_5` copied byte-identically (same checksums) |

```
tools/build.sh :services:shipment:test     # 13 integration (Testcontainers, fake Dapr, fake provider), 3 unit, SDL
tools/sdl-diff.sh shipment                 # empty
tools/image.sh shipment                    # misarch-kotlin/shipment:dev
```
