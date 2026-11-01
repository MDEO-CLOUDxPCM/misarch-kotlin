# user

Users (username, first and last name, birthday, gender, date joined). Users are created from Keycloak's
`user/user/create` event and announced with `user/user/created`, which the other services replicate;
`currentUser { id }` is on the critical path of every Gatling buy iteration. Original: `misarch/user` (Kotlin,
Spring Boot 3.2, graphql-kotlin 7, R2DBC + QueryDSL). Spec and deviations: [docs/specs/user.md](../../docs/specs/user.md),
fixed defects: [docs/fixes/user.md](../../docs/fixes/user.md).

| Contract | Value |
|---|---|
| Dapr app id | `user` |
| HTTP | port 8080: GraphQL `POST /graphql`, `GET /health`, `GET /dapr/subscribe` |
| subscriptions | `user/user/create`, at `POST /subscription/<topic>` |
| publishes | `user/user/created` |
| DB | PostgreSQL, tables `UserEntity` |
| env | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, `OTEL_*`, `DAPR_HTTP_PORT` / `DAPR_HTTP_ENDPOINT` |

## Layout

```
UserApplication.kt          Spring Boot entry point
event/                      UserEvents (topics), EventController (subscriptions), model/ (event DTOs)
graphql/                    Query, Mutation, model/ (User, Name, Gender, connection/), input/, dataloader/,
                            federation/
persistence/                model/ (entities), repository/
service/                    UserService
```

## Build and test

```
tools/build.sh :services:user:test
tools/sdl-diff.sh user                    # empty
tools/image.sh user                       # misarch-kotlin/user:dev
```
