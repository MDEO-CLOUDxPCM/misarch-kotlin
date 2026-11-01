# notification

In-app notifications of users (title, body, sent and read dates). It replicates user ids from `user/user/created`.
It also subscribes to `notification/notification/create`, which no MiSArch service publishes, so notifications come
only from the employee-only `createNotification` mutation. Original: `misarch/notification` (Kotlin, Spring Boot 3.2,
graphql-kotlin 7, R2DBC + QueryDSL). Spec and deviations: [docs/specs/notification.md](../../docs/specs/notification.md),
fixed defects: [docs/fixes/notification.md](../../docs/fixes/notification.md).

| Contract | Value |
|---|---|
| Dapr app id | `notification` |
| HTTP | port 8080: GraphQL `POST /graphql`, `GET /health`, `GET /dapr/subscribe` |
| subscriptions | `user/user/created`, `notification/notification/create`, each at `POST /subscription/<topic>` |
| publishes | nothing |
| DB | PostgreSQL, tables `NotificationEntity`; replicas `UserEntity` |
| env | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, `OTEL_*`, `DAPR_HTTP_PORT` / `DAPR_HTTP_ENDPOINT` |

## Layout

```
NotificationApplication.kt  Spring Boot entry point
event/                      NotificationEvents (topics), EventController (subscriptions), model/ (event DTOs)
graphql/                    Query, Mutation, model/ (Notification, User, connection/), input/, dataloader/,
                            federation/
persistence/                model/ (entities), repository/
service/                    NotificationService, UserService
```

## Build and test

```
tools/build.sh :services:notification:test
tools/sdl-diff.sh notification            # empty
tools/image.sh notification               # misarch-kotlin/notification:dev
```
