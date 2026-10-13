package org.misarch.invoice.event

import org.misarch.common.rust.AxumJson
import org.misarch.common.rust.AxumJson.reqObject
import org.misarch.common.rust.RustDaprRoutes
import org.misarch.invoice.service.InvoiceService
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
import tools.jackson.databind.JsonNode

/**
 * Dapr routes of the original `event/http_event_service.rs` (docs/specs/invoice.md §5). The subscription of
 * `user/user/created` points to `/on-id-creation-event`, which the original did not serve (404 → Dapr dropped every
 * user event, defect D1). FIXED (docs/fixes/invoice.md): the user handler is served under that route too
 * (`/dapr/subscribe` unchanged).
 */
@Configuration(proxyBeanMethods = false)
class EventRoutes(private val invoiceService: InvoiceService) {

    private val logger = LoggerFactory.getLogger(EventRoutes::class.java)

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun invoiceDaprSubscribeRoute(): RouterFunction<ServerResponse> = RustDaprRoutes.subscribe(SUBSCRIPTIONS)

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun invoiceEventRoutes(): RouterFunction<ServerResponse> = coRouter {
        POST("/on-discount-validation-succeded") { request ->
            handle(request, VALIDATION_SUCCEEDED, { data -> OrderEventData.fromJson(data.reqObject("order")) }) {
                invoiceService.createInvoice(it)
            }
        }
        POST("/on-vendor-address-creation-event") { request ->
            handle(request, VENDOR_ADDRESS_CREATED, VendorAddressEventData::fromJson) { invoiceService.onVendorAddressCreated(it) }
        }
        POST("/on-user-creation-event") { request ->
            handle(request, USER_CREATED, UserEventData::fromJson) { invoiceService.onUserCreated(it) }
        }
        POST("/on-id-creation-event") { request ->
            handle(request, USER_CREATED, UserEventData::fromJson) { invoiceService.onUserCreated(it) }
        }
        POST("/on-user-address-creation-event") { request ->
            handle(request, USER_ADDRESS_CREATED, UserAddressEventData::fromJson) { invoiceService.onUserAddressCreated(it) }
        }
        POST("/on-user-address-archived-event") { request ->
            handle(request, USER_ADDRESS_ARCHIVED, UserAddressArchivedEventData::fromJson) { invoiceService.onUserAddressArchived(it) }
        }
    }

    /**
     * axum `Json<Event<T>>` extraction (415/413/400/422), then the topic check (other topic → 500), then [action]
     * (failure → 500), success → 200 `{"status":0}`.
     */
    private suspend fun <T> handle(
        request: ServerRequest,
        expectedTopic: String,
        decode: (JsonNode) -> T,
        action: suspend (T) -> Unit,
    ): ServerResponse {
        val root = when (val parsed = AxumJson.parse(request.headers().firstHeader("Content-Type"), request.awaitBodyOrNull<ByteArray>())) {
            is AxumJson.Rejected -> return ServerResponse.status(parsed.status).buildAndAwait()
            is AxumJson.Parsed -> parsed.root
        }
        val (topic, data) = AxumJson.decode { decodeEnvelope(root, decode) }
            ?: return ServerResponse.status(422).buildAndAwait()
        if (topic != expectedTopic) return ServerResponse.status(500).buildAndAwait()
        return try {
            action(data)
            ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValueAndAwait(RustDaprRoutes.TOPIC_EVENT_OK)
        } catch (e: Exception) {
            logger.warn("Handling {} failed: {}", topic, e.message)
            ServerResponse.status(500).buildAndAwait()
        }
    }

    companion object {
        const val VALIDATION_SUCCEEDED = "discount/order/validation-succeeded"
        const val VENDOR_ADDRESS_CREATED = "address/vendor-address/created"
        const val USER_CREATED = "user/user/created"
        const val USER_ADDRESS_CREATED = "address/user-address/created"
        const val USER_ADDRESS_ARCHIVED = "address/user-address/archived"

        /** `list_topic_subscriptions` (reference/dapr-subscribe/invoice.json). */
        const val SUBSCRIPTIONS =
            """[{"pubsubName":"pubsub","topic":"discount/order/validation-succeeded","route":"/on-discount-validation-succeded"},""" +
                """{"pubsubName":"pubsub","topic":"address/vendor-address/created","route":"/on-vendor-address-creation-event"},""" +
                """{"pubsubName":"pubsub","topic":"user/user/created","route":"/on-id-creation-event"},""" +
                """{"pubsubName":"pubsub","topic":"address/user-address/created","route":"/on-user-address-creation-event"},""" +
                """{"pubsubName":"pubsub","topic":"address/user-address/archived","route":"/on-user-address-archived-event"}]"""
    }
}
