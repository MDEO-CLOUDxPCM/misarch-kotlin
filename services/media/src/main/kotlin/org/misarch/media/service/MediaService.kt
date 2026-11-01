package org.misarch.media.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.misarch.common.event.EventPublisher
import org.misarch.common.graphql.Upload
import org.misarch.media.MediaConfiguration
import org.misarch.media.MediaConfigurationProperties
import org.misarch.media.event.MediaEvents
import org.misarch.media.event.model.MediaDTO
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.util.UUID

/**
 * Service for media files: objects `<id>.<extension>` in the bucket [MediaConfiguration.BUCKET] of the S3-compatible
 * store. There is no database; the bucket listing is the list of media.
 */
@Service
class MediaService(
    private val s3Client: S3Client,
    private val s3Presigner: S3Presigner,
    private val properties: MediaConfigurationProperties,
    private val eventPublisher: EventPublisher,
) {
    private val logger = LoggerFactory.getLogger(MediaService::class.java)

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
     * All media files, in key order (the bucket listing, all pages).
     */
    suspend fun findAllMedia(): List<MediaObject> = listKeys("").map(::MediaObject)

    /**
     * The pre-signed download path of a media file, with scheme and host of the store replaced by the proxy path
     *
     * @param id the id of the media
     */
    suspend fun presignedPath(id: UUID): String {
        val key = listKeys(id.toString()).lastOrNull() ?: throw NoSuchElementException()
        val url = s3Presigner.presignGetObject {
            it.signatureDuration(properties.pathExpirationTime)
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
