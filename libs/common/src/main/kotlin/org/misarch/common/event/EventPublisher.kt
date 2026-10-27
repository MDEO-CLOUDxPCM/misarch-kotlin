package org.misarch.common.event

import kotlinx.coroutines.reactor.awaitSingle
import org.misarch.common.timing.TimingSpans
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import java.net.URI

/**
 * Publishes domain events through the Dapr sidecar's HTTP API:
 * `POST {httpEndpoint}/v1.0/publish/{pubsubName}/{topic}` with the JSON payload ([EventJson]); Dapr wraps it into a
 * CloudEvent (`datacontenttype: application/json`). Topics contain slashes (`user/user/created`) and are passed
 * verbatim. A non-2xx answer throws, so the surrounding mutation or event handler fails.
 */
open class EventPublisher(
    private val webClient: WebClient,
    private val properties: DaprProperties,
) {
    private val logger = LoggerFactory.getLogger(EventPublisher::class.java)

    /**
     * Publishes [message] to [topic] on the default pub/sub component.
     */
    suspend fun publishEvent(topic: String, message: Any) {
        publishEvent(properties.pubsubName, topic, message)
    }

    /**
     * Publishes [message] to [topic] on the pub/sub component [pubsubName].
     */
    suspend fun publishEvent(pubsubName: String, topic: String, message: Any) {
        val uri = URI.create("${properties.httpEndpoint.trimEnd('/')}/v1.0/publish/$pubsubName/$topic")
        logger.debug("Publishing to {}", uri)
        TimingSpans.span(
            { "publish $topic" },
            { it.setAttribute(TimingSpans.TOPIC, topic).setAttribute(TimingSpans.PUBSUB, pubsubName) },
            { null },
        ) {
            webClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(EventJson.toBytes(message))
                .retrieve()
                .toBodilessEntity()
                .awaitSingle()
        }
    }
}
