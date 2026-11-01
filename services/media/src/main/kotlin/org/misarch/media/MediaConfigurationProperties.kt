package org.misarch.media

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration of the media service (env vars `MINIO_ENDPOINT`, `PATH_EXPIRATION_TIME`, `PROXY_PATH`)
 *
 * @property minioEndpoint the S3-compatible store (MinIO, RustFS), e.g. `http://media-minio:9000`
 * @property pathExpirationTime validity of the pre-signed paths in seconds (at most one week)
 * @property proxyPath the path of the reverse proxy in front of the store, replaces scheme and host of pre-signed URLs
 */
@ConfigurationProperties("misarch.media")
data class MediaConfigurationProperties(
    val minioEndpoint: String,
    val pathExpirationTime: Long = 86400,
    val proxyPath: String = "/api/media",
)
