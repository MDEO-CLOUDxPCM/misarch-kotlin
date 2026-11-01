package org.misarch.media

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.http.apache5.Apache5HttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI

/**
 * Configuration of the media service (env vars `MINIO_ENDPOINT`, `PATH_EXPIRATION_TIME`, `PROXY_PATH`)
 *
 * @property minioEndpoint the S3-compatible store (MinIO, RustFS), e.g. `http://media-minio:9000`
 * @property pathExpirationTime validity of the pre-signed paths in seconds (at most one week)
 * @property proxyPath the path of the reverse proxy in front of the store, replaces scheme and host of pre-signed URLs
 */
@ConfigurationProperties(prefix = "media")
class MediaProperties(
    val minioEndpoint: String,
    val pathExpirationTime: Long = 86400,
    val proxyPath: String = "/api/media",
)

/**
 * The S3 client and pre-signer for the media store: path-style addressing, region `eu-central-1`, the store's static
 * credentials, no retries and no additional checksums (MinIO and RustFS do not need them).
 */
@Configuration(proxyBeanMethods = false)
class MediaConfiguration {

    @Bean(destroyMethod = "close")
    fun s3Client(properties: MediaProperties): S3Client = S3Client.builder()
        .endpointOverride(URI.create(properties.minioEndpoint))
        .forcePathStyle(true)
        .region(Region.of(REGION))
        .credentialsProvider(CREDENTIALS)
        .httpClient(Apache5HttpClient.create())
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .overrideConfiguration { it.retryStrategy(AwsRetryStrategy.doNotRetry()) }
        .build()

    @Bean(destroyMethod = "close")
    fun s3Presigner(properties: MediaProperties): S3Presigner = S3Presigner.builder()
        .endpointOverride(URI.create(properties.minioEndpoint))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .region(Region.of(REGION))
        .credentialsProvider(CREDENTIALS)
        .build()

    companion object {
        /** Bucket of the media files */
        const val BUCKET = "media-data"

        /** Region of the bucket */
        const val REGION = "eu-central-1"

        /** Credentials of the media store (fixed in the deployment) */
        const val ACCESS_KEY = "admin"
        const val SECRET_KEY = "password"

        private val CREDENTIALS = StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY))
    }
}
