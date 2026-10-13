# Defect fixes: `media`

Baseline: tag `faithful` (docs/specs/media.md). Tests: `tools/build.sh :services:media:test` → 19 green
(2×8 integration against RustFS 1.0.1 and MinIO, 2 unit, 1 SDL). SDL, app id, routes unchanged; still no subscriptions.

## Fixed

### 1. `getMedias` used only the last listing page (spec Q2)
- **Symptom:** with more than 1000 objects in the bucket, `getMedias` saw only the last ListObjectsV2 page:
  `totalCount` ≤ 1000 and all earlier media were invisible (and `skip`/`first` worked on that page only).
- **Root cause:** the original calls rust-s3 `Bucket::list` (all pages) and then `pop()`s the last page.
- **Fix:** the keys of all pages, in key order. `Media.path` uses the same listing (prefix = id; the result is the same
  last key as before).
- **Load effect:** `getMedias` now lists (and returns) all objects; with ≤ 1000 objects nothing changes.
- **Tests:** `getMedias sees all listing pages (FIXED Q2)` (1001 objects: all returned, window across the page border).

### 2. Lost or misleading `media/media/created` (spec Q1 and step 8)
- **Symptom:** a non-2xx answer of the Dapr publish was ignored, so catalog never replicated the media id and every
  later `createProductVariantVersion(mediaIds: [id])` failed with `Media with id … does not exist.` A transport error
  was reported to the client although the object stayed in the bucket (listed by `getMedias`, unknown to catalog).
  The event was published before the PUT status check.
- **Fix:** PUT status checked first (non-200 → `Media file could not be inserted into MinIO.`, no event); a non-2xx
  publish answer fails with `Publishing media/media/created failed with HTTP <status>.`; on any publish failure the
  object is deleted again (best effort), so an error means "nothing uploaded". Transport-error text unchanged.
- **Tests:** `uploadMedia fails and keeps no object when the publish fails (FIXED Q1)` (Dapr 500, Dapr unreachable,
  then success).

## Payload / contract changes
None (`media/media/created` `{"id"}` unchanged). New error text for a non-2xx publish answer (was success).

## Not fixed (deliberately)
- **Q3 objects stored as `application/octet-stream`**: the pre-signed URLs are served through the frontend's own origin
  (`/api/media` proxy); storing the uploader-chosen `Content-Type` would let an upload be served as `text/html` from
  that origin (stored XSS). Images still render in `<img>`; no effect on load.
- **Q5 `Media.path` does one LIST per media (N+1)** and `_entities(Media)` does not check existence: design (no
  metadata store; the key's extension is only known from the listing), not a wrong result.
- **Q6 non-UUID object names fail `getMedias`** (only inside the requested window): only out-of-band objects; the
  service itself writes UUID keys only.
- **Q4 hard-coded credentials/region/bucket**: configuration, unchanged on purpose (deployment contract).
- **Buyers cannot list/upload, anyone can resolve paths**: intended authorization of the original.
