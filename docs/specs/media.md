# Service spec: `media`

Original: `/home/devbox/workspace/misarch/media` (Rust 2024 edition, axum 0.8.3, async-graphql 7.0.16 with the
multipart `Upload` scalar, rust-s3 0.33.0, mime 0.3.17, reqwest 0.12). Reference SDL: `reference/sdl/media.graphql`
(captured from the running original; it matches `misarch/schemas/media.graphql`).
This service is written like the Kotlin services (`Query`/`Mutation`, a service, configuration beans; no database);
§1-§9 describe the original, §10 the implementation and §11 every visible difference to the original. Fixed defects:
`docs/fixes/media.md`.

## 1. Role in the system

- Owns `Media`: binary files stored as objects `<uuid>.<ext>` in the MinIO bucket `media-data`.
- **There is no metadata store.** The original has no database at all. The bucket listing *is* the data model.
  The compose file sets `MONGODB_URI`, but the code never reads it, and there is no `media-db` container.
  Decision: the port keeps MinIO as the only store and adds **no Postgres**. A metadata table would change
  observable behaviour: objects put into the bucket out of band would not be listed, and listing order and the
  page-size quirk Q2 would differ. It would also add a dependency the deployment does not have.
- Publishes `media/media/created` (consumed by catalog to replicate media ids).
- Pre-signed GET URLs are rewritten to go through an nginx reverse proxy (`/api/media` in the frontend's nginx and
  in `media/nginx`), which forwards to MinIO with `Host: media-minio:9000`.

## 2. Runtime contract of the original (kept except for §11)

| Item | Value |
|---|---|
| HTTP port | 8080 (bind `0.0.0.0:8080`) |
| GraphQL | `POST /` (gateway `routing_url: http://localhost:3500/v1.0/invoke/media/method/`), `application/json` or `multipart/form-data` (GraphQL multipart request spec: parts `operations`, `map`, files). `GET /` = GraphiQL. Single requests only (batch → 400). Unparsable multipart → 400 (body = Rust debug text). No body size limit. |
| Health | `GET /health` → 200 empty (healthcheck `wget -qO - http://localhost:8080/health`) |
| Dapr app-id | `media` (app-port `${EXPERIMENT_CONFIG_SIDECAR_PORT:-8080}`, http, Dapr HTTP 3500) |
| Dapr endpoints served | **none** (no `/dapr/subscribe`; Dapr's probe gets 404, which means no subscriptions) |
| Dapr endpoints called | `POST http://localhost:3500/v1.0/publish/pubsub/media/media/created` (hard-coded host and port) |
| Env vars | `MINIO_ENDPOINT` (**required**; the process panics when it is unset; for example `http://media-minio:9000`). `PATH_EXPIRATION_TIME` (u32 seconds, default 86400; unparsable → 86400). `PROXY_PATH` (default `/api/media`). `OTEL_EXPORTER_OTLP_ENDPOINT` (default `http://localhost:4318`, push to `<endpoint>/v1/metrics` every 5 s, service name `media`). `MONGODB_URI` is set by compose and ignored. |
| MinIO access (hard-coded) | access key `admin`, secret `password`, region `eu-central-1`, bucket `media-data`, **path-style** addressing, SigV4 |
| Startup | try to create the bucket (`PUT /media-data`). Any failure (it already exists, MinIO unreachable) is ignored and the existing bucket is used. The service starts even when MinIO is down. |
| Auth input | `Authorized-User` header (same parsing as review/§4) |

## 3. GraphQL schema (equals the reference SDL)

- `Query.getMedias(first: Int, skip: Int): MediaConnection!` ("Returns all medias of the service.")
- `Query._entities(representations: [_Any!]!): [_Entity]!`, `union _Entity = Media`
- `Mutation.uploadMedia(mediaFile: Upload!): UUID!` (multi-line description as in the reference)
- `type Media @key(fields: "id")` ("Media object with associated path and id."): `id: UUID!` ("Media UUID."),
  `path: String!` ("Pre-signed path for the media.")
- `type MediaConnection @shareable`: `nodes: [Media!]!`, `hasNextPage: Boolean!`, `totalCount: Int!`
- Scalars `UUID`, `Upload`. `first`/`skip` are `usize` (negative → `Failed to parse "Int": Invalid number`).

## 4. Authorization

Same header parsing and `authorize_user` as review (spec review.md §4), always called with `id = None`.
So only **admin or employee** pass:
- no/unparsable header → `Authentication failed. Authorized-User header is not set or could not be parsed.`
- buyer only → ``Authentication failed for user of UUID: `<header id>`. Operation not permitted.``

| Operation | Auth |
|---|---|
| `uploadMedia`, `getMedias` | admin/employee (checked first) |
| `_entities Media`, `Media.path` | none |

## 5. Resolvers

### 5.1 `uploadMedia(mediaFile)`
1. Authorize.
2. The upload value. A multipart file part counts only if it has a `filename` in its Content-Disposition (otherwise the variable
   stays `null` → non-null argument error).
3. Content type = the part's `Content-Type` header, **parsed** by the `mime` crate. A missing or unparsable header gives
   the error `Content type of file upload does not exist.` (the later mime re-parse can then not fail).
4. Extension = mime `subtype()`: the source is lower-cased, parameters are cut at `;`, and a `+suffix` is cut at the **last** `+`.
   Examples: `image/PNG` → `png`; `image/svg+xml` → `svg`; `application/vnd.api+json` → `vnd.api`;
   `text/plain; charset=utf-8` → `plain`; `image/` → `` (empty).
5. Read the whole file into memory. `id` = new UUID v4. Key = `"<id>.<ext>"`.
6. `PUT /media-data/<key>` with **`Content-Type: application/octet-stream`** (rust-s3 `put_object` default; the uploaded
   type is NOT stored). A non-2xx answer → error `Got HTTP <status> with content '<response body>'`. A transport error
   → `reqwest: <reqwest error text>`. In both cases there is no event.
7. Publish `media/media/created`: `POST http://localhost:3500/v1.0/publish/pubsub/media/media/created`,
   `Content-Type: application/json`, body `{"id":"<uuid>"}`. Any HTTP status is ignored. A transport error → GraphQL error
   (`reqwest`'s `error sending request for url (…)`), but the object is already stored (QUIRK Q1).
8. If the PUT status was not exactly 200 → `Media file could not be inserted into MinIO.` (after the event was published;
   with MinIO this does not happen).
   **FIXED** (docs/fixes/media.md): the PUT status is checked before publishing; a non-2xx publish answer fails with
   `Publishing media/media/created failed with HTTP <status>.`; on any publish failure the object is deleted again.
9. Return `id`.

### 5.2 `getMedias(first, skip)`
1. Authorize.
2. List the bucket (ListObjectsV2, prefix `""`, no delimiter, server default page size 1000, following continuation tokens).
   **QUIRK Q2**: only the **last page** is used. With more than 1000 objects, `getMedias` sees only the last ≤ 1000 keys,
   and `totalCount` is the size of that page only. **FIXED** (docs/fixes/media.md): the keys of all pages are used.
3. `total = len(lastPage)`. Window = `lastPage[skip ?? 0 :][: first ?? ∞]` in S3 key order (lexicographic, UTF-8 bytes).
4. Each key in the **window only** → `Media{id}`: Rust `Path::file_stem` of the key. That is the last `/`-separated component
   (trailing `/` ignored), with the extension after the last `.` removed, unless the name starts with `.` and has no other `.`.
   A component `..`, or an empty one → error `File in bucket does not have a name.`. Then UUID parse (lenient uuid crate
   rules) → error text of the uuid crate (for example `invalid character: expected an optional prefix of \`urn:uuid:\`
   followed by [0-9a-fA-F-], found \`x\` at 1`). Any error fails the whole query (`data: null`).
5. `hasNextPage = total > len(window) + skip`. Result `{nodes, hasNextPage, totalCount: total}`.

### 5.3 `Media.path`
1. List with prefix = `id` (lowercase hyphenated). Take the last page, then the **last** key of it. None → ``Media file of UUID: `<id>` not found.``
2. Pre-sign a GET for that key with expiry `PATH_EXPIRATION_TIME`. Expiry > 604800 → error
   `Max expiration for presigned URLs is one week, or 604.800 seconds, got <n> instead`.
3. Result = `PROXY_PATH + <url path> + "?" + <url query>` (plain concatenation). The URL is
   `<MINIO_ENDPOINT>/media-data/<key>` (path-style; key URI-encoded as in SigV4 canonical form). The query is
   **in this order**:
   `X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=admin%2F<yyyyMMdd>%2Feu-central-1%2Fs3%2Faws4_request&X-Amz-Date=<yyyyMMdd'T'HHmmss'Z'>&X-Amz-Expires=<n>&X-Amz-SignedHeaders=host&X-Amz-Signature=<hex>`.
   The signature uses canonical headers `host:<host[:port] of MINIO_ENDPOINT>` and payload `UNSIGNED-PAYLOAD`. The proxy
   forwards with `Host: media-minio:9000`, so the URL validates at MinIO.
   Example: `/api/media/media-data/0b9e…c1.png?X-Amz-Algorithm=…`.
4. No auth, no caching: every resolution runs one LIST plus one pre-signing. `getMedias { nodes { path } }` does N+1 LISTs.

### 5.4 `_entities Media {id}`
Returns `Media{id}` for any parsable id, without checking that it exists (no I/O). `path` then fails if there is no object.
Missing/unparsable id or unknown typename → `Entity not found.` (fails the whole field).

## 6. Events

Published: `media/media/created`, component `pubsub`, payload `{"id": "<uuid>"}`. The trigger is a successful object PUT,
and the publish happens before the final 200-check. Subscribed: none.

## 7. Quirks and defects of the original

- Q1 publish failures are reported to the client although the object is stored. Non-2xx publish answers are ignored.
  **FIXED** (non-2xx = failure, object removed on failure, PUT status checked first).
- Q2 only the last listing page counts (`totalCount` ≤ 1000). **FIXED** (all pages).
- Q3 objects are stored as `application/octet-stream`. The real content type only selects the file extension.
- Q4 hard-coded MinIO credentials and region. `MINIO_ENDPOINT` is required. The bucket-creation error is swallowed.
- Q5 `Media.path` runs a LIST per media (N+1). `_entities` does not check existence.
- Q6 non-UUID object names make `getMedias` fail, but only when they fall inside the requested window.
- Buyers cannot list or upload. Anyone can resolve paths.

## 8. Persistence
MinIO bucket `media-data`, keys `<uuid>.<ext>`, `Content-Type: application/octet-stream`. No Postgres, no Flyway (see §1).

## 9. Tests

`tools/build.sh :services:media:test`: `MediaIntegrationTest` twice (`RustfsMediaIntegrationTest`,
`MinioMediaIntegrationTest`; Testcontainers store, fake Dapr sidecar): GraphQL multipart upload end to end (stored
object, octet-stream, extension, event payload), authorization and content-type errors, publish failure removes the
object, `PROXY_PATH`/`PATH_EXPIRATION_TIME`, `getMedias` order/paging/foreign keys/argument errors, 1001 objects
(all pages), pre-signed path downloads the bytes, `_entities`, `/dapr/subscribe` and `/health`; `MediaSdlTest`
(`tools/sdl-diff.sh media` empty).

## 10. Implementation (services/media)

- No database (R2DBC auto-configuration excluded, Flyway off). `MediaProperties` (`media.*` = `MINIO_ENDPOINT`,
  `PATH_EXPIRATION_TIME`, `PROXY_PATH`), `MediaConfiguration` (`S3Client` and `S3Presigner` of the AWS SDK v2: path
  style, region `eu-central-1`, static credentials `admin`/`password`, no retries, checksums only when required,
  Apache HttpClient 5 transport), `MediaService` (upload, listing, pre-signed path; blocking SDK calls on
  `Dispatchers.IO`), `Query`/`Mutation` (`checkIsEmployee()`), `Media`/`MediaConnection`, `MediaResolver`
  (`ReferenceEntityResolver`: any id, no store access).
- `uploadMedia(mediaFile: Upload!)`: libs/common's `Upload` and `MultipartGraphQLRequestParser`
  (`misarch.graphql.multipart-upload: true`; GraphQL multipart request spec). Extension = the part content type's
  subtype up to a `+` (`image/PNG` → `png`, `image/svg+xml` → `svg`), object `<id>.<ext>` stored as
  `application/octet-stream`; then `media/media/created` `{"id":"<uuid>"}` via `EventPublisher`; a failed publish
  removes the object again and fails the mutation.
- `getMedias`: all listing pages (`listObjectsV2Paginator`), window `drop(skip).take(first)`, ids from the key up to
  the last `.` (`UUID.fromString`), `hasNextPage = total > skip + nodes`; paging arguments `PagingValidation.NON_NEGATIVE`.
- `Media.path`: the last key with prefix `<id>`, pre-signed with the SDK's `S3Presigner` (SigV4 query signing,
  `X-Amz-Expires = PATH_EXPIRATION_TIME`), scheme and host replaced by `PROXY_PATH`.
- Bucket creation at startup (any failure ignored, the service starts with the store down).
- S3 servers: deploy/compose uses **RustFS 1.0.1** (`rustfs/rustfs:1.0.1`) as `media-minio` (MinIO's community server is
  no longer distributed); the original deployment uses MinIO. The integration suite runs against both (RustFS 1.0.1
  and MinIO `bitnamilegacy/minio:2025.7.23`).

## 11. Deviations from the original

Project-wide: OpenTelemetry Java agent, Dapr SDK endpoints (`/dapr/config`, `/healthz`). Visible through the API:

- `GET /dapr/subscribe` answers `200 []` (the Dapr SDK; was 404). Dapr treats both as "no subscriptions".
- `/health` answers `{"status":"UP"}` (was an empty 200); GraphiQL at `/graphiql` (was `GET /`).
- Error shape and texts (graphql-java): `Exception while fetching data (/path) : <message>`; `Unauthorized access` /
  `Unauthorized access: <id> is not an employee or admin` (were `Authentication failed …`); `Limit/Offset must not be
  negative.` (was `Failed to parse "Int": Invalid number`); S3 and publish failures carry the AWS SDK's / WebClient's
  messages (were rust-s3's `Got HTTP …` and reqwest's texts); an unknown media in `path` reads `Media with id <id> does
  not exist` (was ``Media file of UUID: `<id>` not found.``); a non-UUID object name in the window fails with
  `Invalid UUID string: …` (was the uuid crate's text); `UUID.fromString` replaces the uuid crate's lenient forms.
- `_entities(Media)` with an unparsable id gives a `null` element with an error (was `Entity not found.` for the whole
  field).
- Pre-signed paths come from the AWS SDK's presigner: the same SigV4 query parameters, other parameter order; they stay
  valid behind `PROXY_PATH`.
- `PATH_EXPIRATION_TIME` is read as a number of seconds; an unparsable value fails the start (was: default 86400),
  more than one week fails `path` with the SDK's message.
- A file part whose `Content-Type` Spring cannot parse fails the request with HTTP 400 (was `Content type of file upload
  does not exist.`); `mime`-crate details (e.g. `image/png; charset` treated as absent) are not reproduced.
- The defects of `docs/fixes/media.md` are fixed (all listing pages, publish failures).
