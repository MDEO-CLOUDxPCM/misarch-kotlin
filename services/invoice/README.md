# invoice (Kotlin port)

Port of the MiSArch invoice service (original: Rust, axum + async-graphql + MongoDB). Spec, defects and test plan:
[docs/specs/invoice.md](../../docs/specs/invoice.md).

Drop-in contract (unchanged): Dapr app id `invoice`, port 8080, GraphQL at `POST /` (GraphiQL at `GET /`), `GET /health`,
`GET /dapr/subscribe` (exact original JSON, incl. the route `/on-id-creation-event` that is not served → 404),
event routes `/on-discount-validation-succeded`, `/on-vendor-address-creation-event`, `/on-user-creation-event`,
`/on-user-address-creation-event`, `/on-user-address-archived-event`, SDL = `reference/sdl/invoice.graphql`.

**No invoice is ever created and `invoice/invoice/created` is never published - exactly like the original.**
Reproduced defects: discount's payload (`"PLACED"`, `{"cvc":…}`) never deserializes → 422 + endless Dapr redelivery;
vendor-address payload expects snake_case → 422; `user/user/created` routed to a missing path → 404 (dropped);
address events write `user_addresses` while the lookup reads `addresses`, and the lookup's projection drops the
names, so even a matching user fails → 500; the publish URL names pub/sub component `invoice` (topic
`invoice/created`) and ignores Dapr's error. The intended creation path (content rendering, event JSON, publish)
is ported and unit-tested as functions.

## What differs from the original

| Difference | Why | Effect |
|---|---|---|
| Postgres instead of MongoDB: `invoices`, `vendor_address`, `users` (Mongo collection `user`; `user` is reserved), embedded addresses as `jsonb`, `seq` columns for Mongo's natural order | uniform stack | env `SPRING_R2DBC_*`/`SPRING_FLYWAY_URL` replace `MONGODB_URI` |
| OTLP endpoint: the original hard-codes `http://otel-collector:4318`; the port's OpenTelemetry agent reads `OTEL_EXPORTER_OTLP_ENDPOINT` | shared image | deployments must set `OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318` for invoice |
| `GET /health` answers `{"status":"UP"}` (original: empty 200); `/dapr/config`, `/healthz` exist | shared stack | none |
| Bodies of 4xx/5xx event answers (axum/serde error texts) are not reproduced, only the status codes | Dapr reads only the status | none |
| Scalar coercion error texts follow graphql-java for some malformed inputs | graphql-java | only for invalid requests |
