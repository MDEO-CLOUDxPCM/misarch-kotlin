package org.misarch.review.event

import org.misarch.common.rust.AxumJson
import org.misarch.common.rust.AxumJson.reqObject
import org.misarch.common.rust.AxumJson.reqString
import org.misarch.common.rust.AxumJson.reqUuid
import org.misarch.common.rust.RustDaprRoutes
import org.misarch.review.persistence.ReviewStore
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBodyOrNull
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait
import org.springframework.web.reactive.function.server.coRouter
import java.util.UUID

/**
 * Dapr routes of the original `event/http_event_service.rs` (docs/specs/review.md §6):
 * `GET /dapr/subscribe` (exact original JSON: the product-variant topic goes to `/on-topic-event`),
 * `POST /on-topic-event`, `POST /on-product-variant-creation-event` (not subscribed).
 *
 * FIXED (docs/fixes/review.md): `/on-topic-event` also handles `catalog/product-variant/created` (defect D1: it
 * answered 500 forever, so the product-variant replica stayed empty and `createReview` always failed), and all
 * replica inserts are idempotent (a redelivered event answers 200 instead of 500 forever).
 */
@Configuration(proxyBeanMethods = false)
class EventRoutes(private val store: ReviewStore) {

    private val logger = LoggerFactory.getLogger(EventRoutes::class.java)

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun reviewDaprSubscribeRoute(): RouterFunction<ServerResponse> = RustDaprRoutes.subscribe(SUBSCRIPTIONS)

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun reviewEventRoutes(): RouterFunction<ServerResponse> = coRouter {
        POST("/on-topic-event") { request -> onTopicEvent(request) }
        POST("/on-product-variant-creation-event") { request -> onProductVariantCreationEvent(request) }
    }

    /**
     * `on_topic_event`: `Event<EventData {id}>` for user and product creation, `{id, productId}` for product-variant
     * creation (FIXED D1: the variant topic is subscribed to this route).
     */
    private suspend fun onTopicEvent(request: ServerRequest): ServerResponse {
        val root = when (val parsed = parse(request)) {
            is AxumJson.Rejected -> return ServerResponse.status(parsed.status).buildAndAwait()
            is AxumJson.Parsed -> parsed.root
        }
        val (topic, id) = AxumJson.decode {
            val data = root.reqObject("data")
            root.reqString("topic") to data.reqUuid("id")
        } ?: return ServerResponse.status(422).buildAndAwait()
        logger.info("Event {} {}", topic, id)
        return when (topic) {
            USER_CREATED -> insert { store.insertUser(id) }
            PRODUCT_CREATED -> insert { store.insertProduct(id) }
            PRODUCT_VARIANT_CREATED -> {
                val productId = AxumJson.decode { root.reqObject("data").reqUuid("productId") }
                    ?: return ServerResponse.status(422).buildAndAwait()
                insert { store.insertProductVariant(id, productId) }
            }
            else -> ServerResponse.status(500).buildAndAwait()
        }
    }

    /** `on_product_variant_creation_event`: `Event<ProductVariantEventData {id, productId}>`. */
    private suspend fun onProductVariantCreationEvent(request: ServerRequest): ServerResponse {
        val root = when (val parsed = parse(request)) {
            is AxumJson.Rejected -> return ServerResponse.status(parsed.status).buildAndAwait()
            is AxumJson.Parsed -> parsed.root
        }
        val event: Triple<String, UUID, UUID> = AxumJson.decode {
            val data = root.reqObject("data")
            Triple(root.reqString("topic"), data.reqUuid("id"), data.reqUuid("productId"))
        } ?: return ServerResponse.status(422).buildAndAwait()
        return when (event.first) {
            PRODUCT_VARIANT_CREATED -> insert { store.insertProductVariant(event.second, event.third) }
            else -> ServerResponse.status(500).buildAndAwait()
        }
    }

    private suspend fun parse(request: ServerRequest): AxumJson.Result =
        AxumJson.parse(request.headers().firstHeader("Content-Type"), request.awaitBodyOrNull<ByteArray>())

    private suspend fun insert(block: suspend () -> Unit): ServerResponse = try {
        block()
        ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValueAndAwait(RustDaprRoutes.TOPIC_EVENT_OK)
    } catch (e: Exception) {
        logger.warn("Event handling failed: {}", e.message)
        ServerResponse.status(500).buildAndAwait()
    }

    companion object {
        const val USER_CREATED = "user/user/created"
        const val PRODUCT_CREATED = "catalog/product/created"
        const val PRODUCT_VARIANT_CREATED = "catalog/product-variant/created"

        /** `list_topic_subscriptions` (reference/dapr-subscribe/review.json). */
        const val SUBSCRIPTIONS =
            """[{"pubsubName":"pubsub","topic":"user/user/created","route":"/on-topic-event"},""" +
                """{"pubsubName":"pubsub","topic":"catalog/product/created","route":"/on-topic-event"},""" +
                """{"pubsubName":"pubsub","topic":"catalog/product-variant/created","route":"/on-topic-event"}]"""
    }
}
