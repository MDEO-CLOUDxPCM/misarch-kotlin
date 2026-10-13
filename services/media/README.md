# media (Kotlin port)

Port of the MiSArch media service (original: Rust, axum + async-graphql + rust-s3; blobs in MinIO). Spec, quirks and
test plan: [docs/specs/media.md](../../docs/specs/media.md).

Drop-in contract (unchanged): Dapr app id `media`, port 8080, GraphQL at `POST /` (JSON or GraphQL multipart request for
`uploadMedia(mediaFile: Upload!)`), GraphiQL at `GET /`, `GET /health`, no `/dapr/subscribe` (404), publish
`media/media/created` `{"id":"<uuid>"}` to `localhost:3500/v1.0/publish/pubsub/...`, env `MINIO_ENDPOINT` (required),
`PATH_EXPIRATION_TIME` (default 86400), `PROXY_PATH` (default `/api/media`), hard-coded MinIO credentials
`admin`/`password`, region `eu-central-1`, bucket `media-data`, SDL = `reference/sdl/media.graphql`.

**No database**: the original keeps no metadata; the bucket listing is the data model. The port keeps it that way
(a metadata table would change what `getMedias` returns for objects written out of band, the listing order and the
page-size quirk, and add a dependency the deployment does not have). R2DBC auto-configuration and Flyway are off.

Reproduced: objects stored as `application/octet-stream` with the extension from the part's mime subtype; the event is
published before the final status check and its HTTP status is ignored; only the last ListObjectsV2 page (≤ 1000
keys) is listed; one LIST per `path` resolution (N+1); pre-signed URL with rust-s3's parameter order rewritten to
`PROXY_PATH`; `_entities(Media)` without existence check; admin/employee only for `getMedias`/`uploadMedia`.

## What differs from the original

| Difference | Why | Effect |
|---|---|---|
| S3 client: AWS SDK for Java v2 (`S3Client`, path style, URLConnection HTTP client, no retries) instead of rust-s3; pre-signing hand-written (`S3Presigner`) to keep rust-s3's exact URL shape | vendor-neutral, maintained client (MinIO's SDK dropped) | same S3 requests and objects; S3 error answers are reported as `Got HTTP <code> with content '<raw body>'` like rust-s3 |
| S3 server in deploy/compose: **RustFS 1.0.1** (`rustfs/rustfs:1.0.1`) as `media-minio` instead of MinIO | MinIO's community server is no longer distributed (`minio/minio` gone from Docker Hub, quay.io 401) | none for clients: the port only reads `MINIO_ENDPOINT` (+ `PATH_EXPIRATION_TIME`, `PROXY_PATH`) and runs unchanged against MinIO in the original deployment; the test suite runs against RustFS and MinIO (`bitnamilegacy/minio:2025.7.23`) |
| A file part with a `Content-Type` that Spring cannot parse (`not a type`) → HTTP 400 for the request; original: GraphQL error `Content type of file upload does not exist.` | Spring's multipart reader validates part headers | only for malformed uploads |
| Connection errors read `reqwest: error sending request for url (<url>)` / `error sending request for url (<url>)` approximately | no reqwest | only on infrastructure failures |
| `GET /health` answers `{"status":"UP"}` (original: empty 200); `/dapr/config`, `/healthz` exist | shared stack | none |
