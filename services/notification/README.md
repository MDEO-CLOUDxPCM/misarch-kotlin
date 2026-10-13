# notification (Kotlin port)

Drop-in replacement of MiSArch's `notification` service. It manages in-app notifications of users (title, body,
sent and read dates) and replicates user ids from `user/user/created`. It subscribes to
`notification/notification/create`, but no MiSArch service publishes that topic. As a result, notifications only come
from the employee-only `createNotification` mutation, just as in the original.

Original: `misarch/notification` (Kotlin, Spring Boot 3.2, graphql-kotlin 7, R2DBC + QueryDSL, Dapr SDK 1.11).
The behavioural spec, quirks and deviations are in [docs/specs/notification.md](../../docs/specs/notification.md).

| Contract | Value |
|---|---|
| Dapr app id | `notification` |
| HTTP | port 8080: `POST /graphql`, `GET /health`, `GET /dapr/subscribe`, `GET /dapr/config`, `GET /healthz` |
| subscriptions | `user/user/created` → `POST /subscription/user/user/created`<br>`notification/notification/create` → `POST /subscription/notification/notification/create` (payload `{title, body, userId}`) |
| publishes | nothing |
| DB | PostgreSQL. The original Flyway migration `V1_1` is copied byte for byte (checksum -621256545) |
| env | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, `OTEL_*` |

## Structure

The structure is the same as the original:
* `event/`: the subscription controller and its DTOs.
* `graphql/`: the `Query` and `Mutation` (`@Transactional(REQUIRES_NEW)`), the models `Notification` and `User` (which carries `notifications`), connections, inputs, data loaders and federation.
* `persistence/`: the notification and user replica entities, plus their repositories.
* `service/`: the service layer.

## Build and test

```
tools/build.sh :services:notification:test   # 15 tests: 9 integration, 1 SDL, 5 unit
tools/sdl-diff.sh notification               # empty against both the live SDL and misarch/schemas
tools/image.sh notification                  # misarch-kotlin/notification:dev
```
