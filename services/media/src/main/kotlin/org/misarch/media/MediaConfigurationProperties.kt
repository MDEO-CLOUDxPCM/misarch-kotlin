package org.misarch.media

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.convert.DurationUnit
import java.net.URI
import java.time.Duration
import java.time.temporal.ChronoUnit

/**
 * Configuration of the media service (env vars `MINIO_ENDPOINT` (required), `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`,
 * `PATH_EXPIRATION_TIME`, `PROXY_PATH`).
 *
 * @property minioEndpoint the S3-compatible store (MinIO, RustFS), e.g. `http://media-minio:9000`
 * @property accessKey the access key of the store
 * @property secretKey the secret key of the store
 * @property pathExpirationTime validity of the pre-signed paths (a number means seconds; at most one week)
 * @property proxyPath the path of the reverse proxy in front of the store, replaces scheme and host of pre-signed URLs
 */
@ConfigurationProperties("misarch.media")
data class MediaConfigurationProperties(
    val minioEndpoint: URI,
    val accessKey: String = "admin",
    val secretKey: String = "password",
    @DurationUnit(ChronoUnit.SECONDS)
    val pathExpirationTime: Duration = Duration.ofDays(1),
    val proxyPath: String = "/api/media",
)
