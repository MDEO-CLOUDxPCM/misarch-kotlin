package org.misarch.media.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.misarch.common.event.EventPublisher
import org.misarch.common.graphql.Upload
import org.misarch.common.graphql.connection.PagingValidation
import org.misarch.media.MediaConfiguration
import org.misarch.media.MediaConfigurationProperties
import org.misarch.media.event.MediaEvents
import org.misarch.media.event.model.MediaDTO
import org.misarch.media.graphql.model.Media
import org.misarch.media.graphql.model.connection.MediaConnection
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketConfiguration
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.time.Duration
import java.util.UUID

/**
 * Service for media files: objects `<id>.<extension>` in the bucket [MediaConfiguration.BUCKET] of the S3-compatible
 * store. There is no database; the bucket listing is the list of media.
 *
 * @param s3Client client of the store
 * @param s3Presigner pre-signs the download paths
 * @param properties configuration (pre-signed path validity, proxy path)
 * @param eventPublisher publishes `media/media/created`
 */
@Service
class MediaService(
    private val s3Client: S3Client,
    private val s3Presigner: S3Presigner,
    private val properties: MediaConfigurationProperties,
    private val eventPublisher: EventPublisher,
) {
    private val logger = LoggerFactory.getLogger(MediaService::class.java)

    init {
        // the bucket may exist already or the store may be down: the service starts anyway
        try {
            s3Client.createBucket {
                it.bucket(MediaConfiguration.BUCKET)
                    .createBucketConfiguration(
                        CreateBucketConfiguration.builder().locationConstraint(MediaConfiguration.REGION).build()
                    )
            }
        } catch (e: Exception) {
            logger.info("Bucket {} not created: {}", MediaConfiguration.BUCKET, e.message)
        }
    }

    /**
     * Stores a media file under a new id (extension from the content type's subtype, stored as
     * `application/octet-stream`) and publishes `media/media/created`. If the event cannot be published the object is
     * removed again, so a failed upload leaves nothing behind.
     *
     * @param upload the uploaded file
     * @return the id of the new media
     */
    suspend fun uploadMedia(upload: Upload): UUID {
        val contentType = requireNotNull(upload.contentType) { "Content type of file upload does not exist." }
        val extension = MediaType.parseMediaType(contentType).subtype.substringBefore('+')
        val id = UUID.randomUUID()
        val key = "$id.$extension"
        withContext(Dispatchers.IO) {
            s3Client.putObject(
                { it.bucket(MediaConfiguration.BUCKET).key(key).contentType(MediaType.APPLICATION_OCTET_STREAM_VALUE) },
                RequestBody.fromBytes(upload.content)
            )
        }
        try {
            eventPublisher.publishEvent(MediaEvents.MEDIA_CREATED, MediaDTO(id))
        } catch (e: Exception) {
            deleteObjectQuietly(key)
            throw e
        }
        return id
    }

    /**
     * A page of all media files, in key order
     *
     * @param first the maximum number of media to return (null: all)
     * @param skip the number of media to skip
     */
    suspend fun getMedias(first: Int?, skip: Int?): MediaConnection {
        PagingValidation.NON_NEGATIVE.check((skip ?: 0).toLong(), first?.toLong())
        val keys = listKeys("")
        val offset = skip ?: 0
        val nodes = keys.drop(offset).take(first ?: Int.MAX_VALUE).map { Media(UUID.fromString(it.substringBeforeLast('.'))) }
        return MediaConnection(nodes, keys.size > offset + nodes.size, keys.size)
    }

    /**
     * The pre-signed download path of a media file, with scheme and host of the store replaced by the proxy path
     *
     * @param id the id of the media
     */
    suspend fun presignedPath(id: UUID): String {
        val key = listKeys(id.toString()).lastOrNull() ?: throw NoSuchElementException("Media with id $id does not exist")
        val url = s3Presigner.presignGetObject {
            it.signatureDuration(Duration.ofSeconds(properties.pathExpirationTime))
                .getObjectRequest { request -> request.bucket(MediaConfiguration.BUCKET).key(key) }
        }.url().toURI()
        return "${properties.proxyPath}${url.rawPath}?${url.rawQuery}"
    }

    /** The keys of all objects with [prefix] (all listing pages), in key order. */
    private suspend fun listKeys(prefix: String): List<String> = withContext(Dispatchers.IO) {
        s3Client.listObjectsV2Paginator { it.bucket(MediaConfiguration.BUCKET).prefix(prefix) }
            .contents()
            .map { it.key() }
    }

    /** Removes an object whose creation event could not be published (best effort). */
    private suspend fun deleteObjectQuietly(key: String) = withContext(Dispatchers.IO) {
        try {
            s3Client.deleteObject { it.bucket(MediaConfiguration.BUCKET).key(key) }
        } catch (e: Exception) {
            logger.warn("Could not remove {} after a failed publish: {}", key, e.message)
        }
    }
}
