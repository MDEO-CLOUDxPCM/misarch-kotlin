package org.misarch.media.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import org.misarch.common.event.DaprConfiguration
import org.misarch.common.event.DaprProperties
import org.misarch.common.graphql.GraphQLErrorException
import org.misarch.common.rust.RustMime
import org.misarch.common.rust.RustUpload
import org.misarch.common.rust.RustUuid
import org.misarch.media.graphql.model.Media
import org.misarch.media.graphql.model.MediaConnection
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.net.URI
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.apache5.Apache5HttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketConfiguration
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.util.UUID

/**
 * Business logic of the media service (original `graphql/mutation.rs`, `graphql/query.rs`, `graphql/model/media.rs`,
 * `main.rs`). The bucket listing is the data model; exact semantics: docs/specs/media.md §5.
 */
@Service
class MediaService(
    @Value("\${media.minio-endpoint}") private val minioEndpoint: String,
    @Value("\${media.path-expiration-time}") pathExpirationTime: String,
    @Value("\${media.proxy-path}") private val proxyPath: String,
    @Qualifier(DaprConfiguration.DAPR_WEB_CLIENT) private val webClient: WebClient,
    private val daprProperties: DaprProperties,
) {
    private val logger = LoggerFactory.getLogger(MediaService::class.java)

    /** `PATH_EXPIRATION_TIME` parsed as `u32`, default 86400. */
    private val expirySecs: Long = pathExpirationTime.toLongOrNull()?.takeIf { it in 0..4294967295L } ?: 86400L

    /**
     * Vendor-neutral S3 client (AWS SDK v2) configured like rust-s3's `Bucket` of the original: custom endpoint
     * (`MINIO_ENDPOINT`), path-style, region `eu-central-1`, static credentials, no retries, no extra checksums.
     */
    private val client: S3Client = S3Client.builder()
        .endpointOverride(URI.create(minioEndpoint))
        .forcePathStyle(true)
        .region(Region.of(REGION))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
        .httpClient(Apache5HttpClient.create())
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .overrideConfiguration { it.retryStrategy(AwsRetryStrategy.doNotRetry()) }
        .build()

    private val presigner = S3Presigner(minioEndpoint, ACCESS_KEY, SECRET_KEY, REGION)

    init {
        // initialize_minio_media_data_bucket: create the bucket, ignore every failure (exists, store down)
        try {
            client.createBucket(
                CreateBucketRequest.builder().bucket(BUCKET)
                    .createBucketConfiguration(CreateBucketConfiguration.builder().locationConstraint(REGION).build())
                    .build()
            )
        } catch (e: Exception) {
            logger.info("Bucket {} not created: {}", BUCKET, e.message)
        }
    }

    // ---------------------------------------------------------------- mutation

    /**
     * `upload_media` after authorization (§5.1). FIXED (docs/fixes/media.md): the PUT status is checked before the
     * event is published (the original published `media/media/created` first), a non-2xx publish answer is a failure
     * (it was ignored, so catalog never learned of the media), and a failed publish removes the stored object again
     * (the original reported an error but kept an object no other service knows).
     */
    suspend fun uploadMedia(upload: RustUpload): UUID {
        val contentType = upload.contentType ?: throw GraphQLErrorException("Content type of file upload does not exist.")
        val extension = RustMime.parse(contentType)?.subtype
            ?: throw GraphQLErrorException("mime parse error: an invalid token was encountered")
        val id = UUID.randomUUID()
        val key = "$id.$extension"
        val status = putObject(key, upload.content)
        if (status != 200) throw GraphQLErrorException("Media file could not be inserted into MinIO.")
        try {
            publishMediaCreated(id)
        } catch (e: GraphQLErrorException) {
            deleteObjectQuietly(key)
            throw e
        }
        return id
    }

    /** Best-effort removal of an object whose creation event could not be published. */
    private suspend fun deleteObjectQuietly(key: String) = withContext(Dispatchers.IO) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(key).build())
        } catch (e: Exception) {
            logger.warn("Could not remove {} after a failed publish: {}", key, e.message)
        }
    }

    /** rust-s3 `put_object`: `Content-Type: application/octet-stream`; non-2xx → `Got HTTP <code> with content '<body>'`. */
    private suspend fun putObject(key: String, content: ByteArray): Int = withContext(Dispatchers.IO) {
        try {
            client.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key(key).contentType(OCTET_STREAM).build(),
                RequestBody.fromBytes(content),
            ).sdkHttpResponse().statusCode()
        } catch (e: Exception) {
            throw s3Error(e, "$minioEndpoint/$BUCKET/$key")
        }
    }

    /**
     * `send_media_created_event`: `POST localhost:3500/v1.0/publish/pubsub/media/media/created` `{"id":"<uuid>"}`.
     * FIXED: a non-2xx answer fails (the original ignored the status).
     */
    private suspend fun publishMediaCreated(id: UUID) {
        val url = "${daprProperties.httpEndpoint.trimEnd('/')}/v1.0/publish/pubsub/media/media/created"
        val status = try {
            webClient.post().uri(URI.create(url)).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"id":"$id"}""")
                .exchangeToMono { response -> response.releaseBody().thenReturn(response.statusCode().value()) }
                .awaitSingleOrNull()
        } catch (e: Exception) {
            throw GraphQLErrorException("error sending request for url ($url)")
        }
        if (status == null || status !in 200..299) {
            throw GraphQLErrorException("Publishing media/media/created failed with HTTP $status.")
        }
    }

    // ---------------------------------------------------------------- queries

    /** `get_medias` after authorization (§5.2). FIXED (Q2): all listing pages count (was: only the last one). */
    suspend fun getMedias(first: Long?, skip: Long?): MediaConnection {
        val keys = listKeys("")
        val total = keys.size
        val skipValue = skip ?: 0L
        val window = keys.asSequence().drop(skipValue.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .take((first ?: Long.MAX_VALUE).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).toList()
        val nodes = window.map { key ->
            val stem = RustPath.fileStem(key) ?: throw GraphQLErrorException("File in bucket does not have a name.")
            try {
                Media(RustUuid.parse(stem))
            } catch (e: RustUuid.ParseException) {
                throw GraphQLErrorException(e.message ?: "")
            }
        }
        val hasNextPage = total.toLong() > nodes.size + skipValue
        return MediaConnection(nodes, hasNextPage, total)
    }

    /** `Media.path` (§5.3): last key with prefix `id`, pre-signed and rewritten behind `PROXY_PATH`. */
    suspend fun presignedPath(id: UUID): String {
        val key = listKeys(id.toString()).lastOrNull()
            ?: throw GraphQLErrorException("Media file of UUID: `$id` not found.")
        val url = try {
            URI.create(presigner.presignGet(BUCKET, key, expirySecs))
        } catch (e: IllegalArgumentException) {
            throw GraphQLErrorException(e.message ?: "")
        }
        return "$proxyPath${url.rawPath}?${url.rawQuery ?: ""}"
    }

    /**
     * rust-s3 `Bucket::list(prefix, None)`: all ListObjectsV2 pages, in key order. FIXED (Q2): the keys of all pages
     * (the original `pop()`ed only the last page).
     */
    private suspend fun listKeys(prefix: String): List<String> = withContext(Dispatchers.IO) {
        var token: String? = null
        val keys = mutableListOf<String>()
        do {
            val result = try {
                client.listObjectsV2(
                    ListObjectsV2Request.builder().bucket(BUCKET).prefix(prefix).continuationToken(token).build()
                )
            } catch (e: Exception) {
                throw s3Error(e, "$minioEndpoint/$BUCKET/")
            }
            result.contents().mapTo(keys) { it.key() }
            token = if (result.isTruncated == true) result.nextContinuationToken() else null
        } while (token != null)
        keys
    }

    /** rust-s3 error texts: `Got HTTP <code> with content '<body>'` (non-2xx) or `reqwest: ...` (transport). */
    private fun s3Error(e: Exception, url: String): GraphQLErrorException =
        if (e is AwsServiceException) {
            val body = e.awsErrorDetails()?.rawResponse()?.asUtf8String() ?: ""
            GraphQLErrorException("Got HTTP ${e.statusCode()} with content '$body'")
        } else {
            GraphQLErrorException("reqwest: error sending request for url ($url)")
        }

    companion object {
        /** Hard-coded in the original (`main.rs`). */
        const val BUCKET = "media-data"
        const val REGION = "eu-central-1"
        const val ACCESS_KEY = "admin"
        const val SECRET_KEY = "password"
        const val OCTET_STREAM = "application/octet-stream"
    }
}
