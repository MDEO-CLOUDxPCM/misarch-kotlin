# media

Media files of MiSArch (product images): objects `<uuid>.<ext>` in the bucket `media-data` of an S3-compatible store;
no database (the bucket listing is the list of media). Originally a Rust service (axum + async-graphql + rust-s3);
spec and every difference to the original: [docs/specs/media.md](../../docs/specs/media.md), fixed defects:
[docs/fixes/media.md](../../docs/fixes/media.md).

- GraphQL at `POST /` (JSON, or a GraphQL multipart request for `uploadMedia(mediaFile: Upload!)`), port 8080,
  `GET /health`, Dapr app id `media`; no subscriptions; publishes `media/media/created` `{"id":"<uuid>"}`.
- Env: `MINIO_ENDPOINT` (required), `PATH_EXPIRATION_TIME` (seconds, default 86400), `PROXY_PATH` (default
  `/api/media`); credentials `admin`/`password`, region `eu-central-1` and bucket `media-data` are fixed.
- Store: MinIO in the original deployment, RustFS 1.0.1 in deploy/compose; the tests run against both.

## Layout

```
MediaConfiguration.kt     MediaProperties (media.*), S3Client and S3Presigner beans (AWS SDK v2, path style)
event/                    MediaEvents (topic), model/MediaDTO
graphql/                  Query (getMedias), Mutation (uploadMedia), model/ (Media, MediaConnection), federation/
service/                  MediaService (upload + event, listing, pre-signed paths)
```

Tests: `tools/build.sh :services:media:test`; SDL: `tools/sdl-diff.sh media` (empty).
