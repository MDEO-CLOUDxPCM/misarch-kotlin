package org.misarch.wishlist.event

import org.misarch.common.rust.asyncgraphql.AxumJsonHandler
import org.misarch.wishlist.service.WishlistService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.coRouter

/**
 * Dapr pub/sub route of the original (`event/http_event_service.rs`). The subscription list (`GET /dapr/subscribe`)
 * is served byte-identically by `AsyncGraphqlRoutes` (libs/common) from `dapr-subscribe.json`. Request bodies are CloudEvents; only `topic` and `data.id`
 * are read (axum `Json<Event>`, see [AxumJsonHandler] for the status codes).
 */
@Configuration(proxyBeanMethods = false)
class EventRoutes(private val wishlistService: WishlistService) {

    @Bean
    fun eventRouter(): RouterFunction<ServerResponse> = coRouter {
        POST(WishlistEvents.ON_TOPIC_EVENT_ROUTE) { request ->
            AxumJsonHandler.handle(request) { event ->
                AxumJsonHandler.requireObject(event, "event")
                val topic = AxumJsonHandler.requireString(event, "topic", "event")
                val data = AxumJsonHandler.requireObject(AxumJsonHandler.requireField(event, "data", "event"), "data")
                val id = AxumJsonHandler.requireUuid(data, "id", "data")
                when (topic) {
                    WishlistEvents.PRODUCT_VARIANT_CREATED -> wishlistService.registerProductVariant(id)
                    WishlistEvents.USER_CREATED -> wishlistService.registerUser(id)
                    else -> AxumJsonHandler.internalServerError()
                }
            }
        }
    }
}

/**
 * Topics and routes of the wishlist service (pub/sub component `pubsub`).
 */
object WishlistEvents {
    const val USER_CREATED = "user/user/created"
    const val PRODUCT_VARIANT_CREATED = "catalog/product-variant/created"
    const val ON_TOPIC_EVENT_ROUTE = "/on-topic-event"
}
