package org.misarch.media

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException
import software.amazon.awssdk.services.s3.model.CreateBucketConfiguration
import software.amazon.awssdk.services.s3.model.NoSuchBucketException

/**
 * Creates the bucket [MediaConfiguration.BUCKET] at startup unless it exists. Any other failure (e.g. the store is
 * not reachable) stops the startup. `misarch.media.create-bucket: false` skips it (for tests without a store).
 *
 * @property s3Client client of the store
 */
@Component
@ConditionalOnProperty(name = ["misarch.media.create-bucket"], havingValue = "true", matchIfMissing = true)
class MediaBucketInitializer(private val s3Client: S3Client) : ApplicationRunner {

    private val logger = LoggerFactory.getLogger(MediaBucketInitializer::class.java)

    override fun run(args: ApplicationArguments) {
        try {
            s3Client.headBucket { it.bucket(MediaConfiguration.BUCKET) }
            logger.info("Bucket {} exists", MediaConfiguration.BUCKET)
        } catch (e: NoSuchBucketException) {
            createBucket()
        }
    }

    private fun createBucket() {
        try {
            s3Client.createBucket {
                it.bucket(MediaConfiguration.BUCKET).createBucketConfiguration(
                    CreateBucketConfiguration.builder().locationConstraint(MediaConfiguration.REGION).build()
                )
            }
            logger.info("Bucket {} created", MediaConfiguration.BUCKET)
        } catch (e: BucketAlreadyOwnedByYouException) {
            logger.info("Bucket {} was created concurrently", MediaConfiguration.BUCKET)
        }
    }
}
