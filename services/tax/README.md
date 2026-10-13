# tax (Kotlin port)

Port of [misarch/tax](https://github.com/MiSArch/tax) (Kotlin 1.9 / Spring Boot 3.2 / graphql-kotlin 7 / QueryDSL)
onto the uniform stack (see the root README). Behavioural spec, quirks and deviations:
[docs/specs/tax.md](../../docs/specs/tax.md).

Owns tax rates and their immutable versions (employee-only API). It publishes `tax/tax-rate/created` (to catalog) and
`tax/tax-rate-version/created` (to order). It consumes no events and makes no synchronous calls.

| | |
|---|---|
| Port / health | 8080, `GET /health` |
| GraphQL | `POST /graphql` (subgraph `tax`, federation v2.5), `GET /sdl` |
| Dapr app id | `tax`; `GET /dapr/subscribe` → `[]` |
| Publishes | `tax/tax-rate/created`, `tax/tax-rate-version/created` |
| ECS variables | none (`/ecs/*` → 404 like the original) |
| Env | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, `DAPR_HTTP_PORT`, `OTEL_*` |
| DB | Postgres, original Flyway migration `V1_1__create_relations.sql` copied byte-identically |

```
tools/build.sh :services:tax:test     # 10 integration (Testcontainers, fake Dapr), SDL
tools/sdl-diff.sh tax                 # empty
tools/image.sh tax                    # misarch-kotlin/tax:dev
```
