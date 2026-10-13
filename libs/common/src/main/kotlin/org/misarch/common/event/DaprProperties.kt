package org.misarch.common.event

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Dapr sidecar settings (`misarch.dapr.*`).
 *
 * @property httpEndpoint base URL of the sidecar's HTTP API; default from the Dapr env vars
 *   `DAPR_HTTP_ENDPOINT` or `http://localhost:${DAPR_HTTP_PORT:3500}`
 * @property pubsubName the pub/sub component used for all domain events (`pubsub`)
 */
@ConfigurationProperties("misarch.dapr")
data class DaprProperties(
    val httpEndpoint: String = "http://localhost:3500",
    val pubsubName: String = "pubsub",
)
