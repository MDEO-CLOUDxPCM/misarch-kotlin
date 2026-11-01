# media

Media files (product images), stored as objects `<uuid>.<ext>` in the bucket `media-data` of an S3-compatible store;
there is no database, the bucket listing is the list of media. Original: `misarch/media` (Rust, axum, async-graphql,
rust-s3). Spec and deviations: [docs/specs/media.md](../../docs/specs/media.md), fixed defects:
[docs/fixes/media.md](../../docs/fixes/media.md).

| Contract | Value |
|---|---|
| Dapr app id | `media` |
| HTTP | port 8080: GraphQL `POST /` (gateway: `/v1.0/invoke/media/method/`; JSON, or a GraphQL multipart request for `uploadMedia(mediaFile: Upload!)`), `GET /health`, `GET /dapr/subscribe` |
| subscriptions | none |
| publishes | `media/media/created` |
| DB | none: S3 bucket `media-data` (MinIO in the MiSArch deployment, RustFS 1.0.1 in deploy/compose; the tests run against both); credentials `admin`/`password` and region `eu-central-1` are fixed |
| env | `MINIO_ENDPOINT` (required), `PATH_EXPIRATION_TIME` (seconds, default 86400), `PROXY_PATH` (default `/api/media`), `OTEL_*`, `DAPR_HTTP_PORT` / `DAPR_HTTP_ENDPOINT` |

## Layout

```
MediaApplication.kt         Spring Boot entry point
MediaConfiguration*.kt      MediaConfigurationProperties (misarch.media.*), S3Client and S3Presigner beans (AWS SDK v2)
event/                      MediaEvents (topics), model/ (event DTOs)
graphql/                    Query, Mutation, model/ (Media, connection/), federation/
service/                    MediaService (upload and event, listing, pre-signed paths)
```

## Build and test

```
tools/build.sh :services:media:test
tools/sdl-diff.sh media                   # empty
tools/image.sh media                      # misarch-kotlin/media:dev
```
