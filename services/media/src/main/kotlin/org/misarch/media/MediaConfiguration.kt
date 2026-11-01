package org.misarch.media

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
 * The S3 client and pre-signer for the media store: path-style addressing, region `eu-central-1`, the store's static
 * credentials, no retries and no additional checksums (MinIO and RustFS do not need them).
 */
@Configuration(proxyBeanMethods = false)
class MediaConfiguration {

    @Bean(destroyMethod = "close")
    fun s3Client(properties: MediaConfigurationProperties): S3Client = S3Client.builder()
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
    fun s3Presigner(properties: MediaConfigurationProperties): S3Presigner = S3Presigner.builder()
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
