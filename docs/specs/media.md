# Port spec: `media`

Original: `/home/devbox/workspace/misarch/media` (Rust 2024 edition, axum 0.8.3, async-graphql 7.0.16 with the
multipart `Upload` scalar, rust-s3 0.33.0 with default features `fail-on-err` and `tokio-native-tls`, mime 0.3.17,
reqwest 0.12). Crate `misarch-media`.
Reference SDL: `reference/sdl/media.graphql` (captured from the running original); it matches the code and `misarch/schemas/media.graphql`.

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

## 2. Runtime contract (must stay identical)

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

## 7. Quirks and defects (reproduced)

- Q1 publish failures are reported to the client although the object is stored. Non-2xx publish answers are ignored.
  **FIXED** (non-2xx = failure, object removed on failure, PUT status checked first).
- Q2 only the last listing page counts (`totalCount` ≤ 1000). **FIXED** (all pages).
- Q3 objects are stored as `application/octet-stream`. The real content type only selects the file extension.
- Q4 hard-coded MinIO credentials and region. `MINIO_ENDPOINT` is required. The bucket-creation error is swallowed.
- Q5 `Media.path` runs a LIST per media (N+1). `_entities` does not check existence.
- Q6 non-UUID object names make `getMedias` fail, but only when they fall inside the requested window.
- Buyers cannot list or upload. Anyone can resolve paths.

### Not reproduced (documented deviations)
- Exact transport-error wording (`reqwest: …`) and the 400 debug text of malformed multipart bodies; the error is
  still a GraphQL error or a 400.
- S3 store of the Kotlin deployment: RustFS instead of MinIO (§10); S3 client: AWS SDK v2 instead of rust-s3. Same
  S3 requests (path style, SigV4, ListObjectsV2 with the server's default page size 1000), same stored objects.
- A multipart file part whose `Content-Type` header Spring's multipart reader cannot parse (e.g. `not a type`) fails
  the whole request with HTTP 400; the original (multer) treats it as absent → `Content type of file upload does not
  exist.`. Headers Spring accepts but the `mime` crate rejects (e.g. `image/png; charset`) behave like the original.
- The `X-Amz-Signature` value and `X-Amz-Date` differ per call in both implementations; the structure and parameter order are
  reproduced exactly.
- GraphQL scalar coercion wording, lenient UUID forms and GraphiQL: same rules as the other Rust ports.

## 8. Persistence
MinIO bucket `media-data`, keys `<uuid>.<ext>`, `Content-Type: application/octet-stream`. No Postgres, no Flyway (see §1).

## 9. Test plan (proves equivalence)

Unit:
- mime subtype extraction table (§5.1 step 4) incl. uppercase, `+suffix`, multiple `+`, params, empty subtype; invalid header → missing.
- `file_stem` table (`a.png`, `a.b.c`, `.hidden`, `dir/a.png`, `a.`, `noext`, `..`).
- Pre-signer: AWS SigV4 test vector (the documented `GET /test.txt` pre-sign example from the AWS docs, adapted to its
  parameters), exact parameter order and percent-encoding; rewrite to `PROXY_PATH`; expiry > 604800 → exact error text.
- Header auth matrix (admin, employee, buyer, none, bad JSON).

Integration (Testcontainers: RustFS 1.0.1 and MinIO, same suite; fake Dapr HTTP server):
- `uploadMedia` multipart as admin: object `<id>.png` exists with `application/octet-stream`; exactly one publish
  `{"id":"<id>"}` to `/v1.0/publish/pubsub/media/media/created`; returns the id. Buyer and no header → auth errors and
  no object; no part content type → exact error; fake Dapr returning 500 → success; Dapr down → error but the object exists.
- `getMedias`: empty bucket → `{[], false, 0}`; ordering by key; `first`/`skip`/`hasNextPage`; foreign non-UUID key
  inside/outside the window; > 1000 objects (1001) → only the last page (1 node, totalCount 1).
- `Media.path`: shape `/api/media/media-data/<key>?X-Amz-Algorithm=…` with the exact parameter order; the URL (with
  `PROXY_PATH` replaced by the MinIO endpoint) downloads the original bytes; unknown id → exact error; custom
  `PROXY_PATH`/`PATH_EXPIRATION_TIME` honoured.
- `_entities` Media → `{id}`; a missing object then fails only on `path`.
- Startup with an existing bucket and with MinIO down (the service starts; `/health` 200).
- `GET /dapr/subscribe` → 404. SDL: `tools/sdl-diff.sh media` empty.

## 10. Port implementation notes (services/media)

- No database (R2DBC auto-configuration excluded, Flyway off).
- **S3 client**: vendor-neutral AWS SDK for Java v2 (`S3Client`, JDK `URLConnection` HTTP client, on
  `Dispatchers.IO`), configured like rust-s3's `Bucket`: endpoint `MINIO_ENDPOINT`, path-style, region `eu-central-1`,
  static credentials `admin`/`password`, no retries, checksums only when required. `createBucket` at startup (with
  `LocationConstraint eu-central-1`; any failure ignored), `putObject` (`application/octet-stream`, the real HTTP
  status is checked against 200 like the original), page-by-page `listObjectsV2` keeping only the last page (§5.2),
  pre-signing by the service's own `S3Presigner` (rust-s3's URL shape and parameter order, SigV4).
- **S3 server**: the original deployment uses MinIO (`minio/minio`, no longer pullable: Docker Hub repository removed,
  quay.io 401; the calibration setup runs media without it). deploy/compose of misarch-kotlin uses **RustFS 1.0.1**
  (`rustfs/rustfs:1.0.1`) under the unchanged host name `media-minio` (MINIO_ENDPOINT and the frontend proxy's Host
  header depend on it). The port reads only the original's configuration (`MINIO_ENDPOINT`, `PATH_EXPIRATION_TIME`,
  `PROXY_PATH`; credentials, region and bucket are hard-coded as in the original), so the same image runs against
  MinIO in the original deployment and against RustFS in the Kotlin one. The integration suite runs against both
  (RustFS 1.0.1 and MinIO `bitnamilegacy/minio:2025.7.23`): stored objects (key, bytes, `application/octet-stream`),
  listing order and paging (1001 objects → last page), pre-signed downloads behave identically.
- Multipart requests: `RustGraphQLRequestParser` (libs/common) builds `RustUpload` values (filename, parsed part
  content type, bytes); the `Upload` scalar accepts only those.
- Error texts: S3 error answers → `Got HTTP <code> with content '<raw body>'` (raw XML from the SDK, like rust-s3);
  transport errors → `reqwest: error sending request for url (<url>)` (approximation of reqwest's text).
- In the calibration deployment `media-minio` is absent; the original then answers every storage operation with a
  transport error, and so does the port (bucket creation failure is ignored at startup).

## Integration fixes (2026-10-13, docs/INTEGRATION.md §4.1)

Found by the whole-system differential run against the running original:
- F5: `GET /health` answers 200 with an empty body like the Rust original (actuator web endpoints off).
