# address

User addresses (which can be archived) and vendor addresses, including the current vendor address
(`vendorAddress`). It replicates user ids from `user/user/created`. Original: `misarch/address` (Kotlin, Spring
Boot 3.2, graphql-kotlin 7, R2DBC + QueryDSL). Fixed defects: [docs/fixes/address.md](../../docs/fixes/address.md).

| Contract | Value |
|---|---|
| Dapr app id | `address` |
| HTTP | port 8080: GraphQL `POST /graphql`, `GET /health`, `GET /dapr/subscribe` |
| subscriptions | `user/user/created`, at `POST /subscription/<topic>` |
| publishes | `address/user-address/created`, `address/user-address/archived`, `address/vendor-address/created` |
| DB | PostgreSQL, tables `AddressEntity` (user and vendor addresses); replicas `UserEntity` |
| env | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, `OTEL_*`, `DAPR_HTTP_PORT` / `DAPR_HTTP_ENDPOINT` |

## Layout

```
AddressApplication.kt       Spring Boot entry point
event/                      AddressEvents (topics), EventController (subscriptions), model/ (event DTOs)
graphql/                    Query, Mutation, model/ (Address, UserAddress, VendorAddress, Name, User, connection/),
                            input/, dataloader/, federation/
persistence/                model/ (entities), repository/
service/                    UserAddressService, VendorAddressService, UserService
```

## Build and test

```
tools/build.sh :services:address:test
tools/sdl-diff.sh address                 # empty
tools/image.sh address                    # misarch-kotlin/address:dev
```
