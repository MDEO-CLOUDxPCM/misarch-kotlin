# user (Kotlin port)

Drop-in replacement of MiSArch's `user` service. It owns users (username, first and last name, birthday, gender,
date joined). Users are created from Keycloak's `user/user/create` event, and the service announces them with
`user/user/created`, which nine services replicate. `currentUser { id }` is on the critical path of every Gatling buy
iteration.

Original: `misarch/user` (Kotlin, Spring Boot 3.2, graphql-kotlin 7, R2DBC + QueryDSL, Dapr SDK 1.11).
The behavioural spec, the quirks and the deviations are in [docs/specs/user.md](../../docs/specs/user.md).

| Contract | Value |
|---|---|
| Dapr app id | `user` |
| HTTP | port 8080: `POST /graphql`, `GET /health`, `GET /dapr/subscribe`, `GET /dapr/config`, `GET /healthz` |
| subscriptions | `user/user/create` → `POST /subscription/user/user/create` (payload `{id, username, firstName, lastName}`) |
| publishes | `user/user/created`: `{"id","username","firstName","lastName","dateJoined"}`. `dateJoined` is `ISO_OFFSET_DATE_TIME` of the value stored in the DB, with µs precision |
| DB | PostgreSQL. The original Flyway migrations are copied byte for byte: `V1_1` (checksum 25115277) and `V1_2` (checksum -1834563498) |
| env | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, `OTEL_*`, `DAPR_HTTP_PORT` / `DAPR_HTTP_ENDPOINT` |

## Structure

The structure is the same as the original:
* `event/` holds `UserEvents`, the `EventController` (`@Topic`) and the DTOs.
* `graphql/` holds `Query`, `Mutation`, `model/` (`User` with field-level authorization, `Name`, `Gender`), `model/connection/`, `input/` (`UpdateUserInput` with `OptionalInput`), `dataloader/` and `federation/`.
* `persistence/` holds `UserEntity` and the `UserRepository` with the native insert.
* `service/` holds `UserService`.

QueryDSL was replaced by `libs/common`'s `BaseConnection`.

## Build and test

```
tools/build.sh :services:user:test     # 15 tests: 12 integration (Postgres + fake Dapr), 1 SDL, 2 unit
tools/sdl-diff.sh user                 # empty, against both the live SDL and misarch/schemas
tools/image.sh user                    # misarch-kotlin/user:dev
```
