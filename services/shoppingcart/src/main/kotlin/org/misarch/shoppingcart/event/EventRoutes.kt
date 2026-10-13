package org.misarch.shoppingcart.event

import org.misarch.common.rust.asyncgraphql.AxumJsonHandler
import org.misarch.shoppingcart.service.ShoppingcartService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.coRouter

/**
 * Dapr pub/sub routes of the original (`event/http_event_service.rs`). The subscription list (`GET /dapr/subscribe`)
 * is served byte-identically by `AsyncGraphqlRoutes` (libs/common) from `dapr-subscribe.json`. Request bodies are CloudEvents; only `topic` and `data` are
 * read (axum `Json<Event<T>>`, see [AxumJsonHandler] for the status codes).
 */
@Configuration(proxyBeanMethods = false)
class EventRoutes(private val shoppingcartService: ShoppingcartService) {

    @Bean
    fun eventRouter(): RouterFunction<ServerResponse> = coRouter {
        POST(ShoppingcartEvents.ON_TOPIC_EVENT_ROUTE) { request ->
            AxumJsonHandler.handle(request) { event ->
                AxumJsonHandler.requireObject(event, "event")
                val topic = AxumJsonHandler.requireString(event, "topic", "event")
                val data = AxumJsonHandler.requireObject(AxumJsonHandler.requireField(event, "data", "event"), "data")
                val id = AxumJsonHandler.requireUuid(data, "id", "data")
                when (topic) {
                    ShoppingcartEvents.PRODUCT_VARIANT_CREATED -> shoppingcartService.registerProductVariant(id)
                    ShoppingcartEvents.USER_CREATED -> shoppingcartService.registerUser(id)
                    else -> AxumJsonHandler.internalServerError()
                }
            }
        }
        POST(ShoppingcartEvents.ON_ORDER_CREATION_EVENT_ROUTE) { request ->
            AxumJsonHandler.handle(request) { event ->
                AxumJsonHandler.requireObject(event, "event")
                val topic = AxumJsonHandler.requireString(event, "topic", "event")
                val data = AxumJsonHandler.requireObject(AxumJsonHandler.requireField(event, "data", "event"), "data")
                AxumJsonHandler.requireUuid(data, "id", "data")
                val userId = AxumJsonHandler.requireUuid(data, "userId", "data")
                val shoppingCartItemIds = AxumJsonHandler.requireArray(data, "orderItems", "data").mapIndexed { index, item ->
                    val path = "data.orderItems[$index]"
                    AxumJsonHandler.requireObject(item, path)
                    val itemId = AxumJsonHandler.requireUuid(item, "shoppingCartItemId", path)
                    AxumJsonHandler.requireU64(item, "count", path)
                    itemId
                }
                when (topic) {
                    ShoppingcartEvents.ORDER_CREATED -> shoppingcartService.removeOrderedItems(userId, shoppingCartItemIds)
                    else -> AxumJsonHandler.internalServerError()
                }
            }
        }
    }
}

/**
 * Topics and routes of the shopping cart service (pub/sub component `pubsub`).
 */
object ShoppingcartEvents {
    const val USER_CREATED = "user/user/created"
    const val PRODUCT_VARIANT_CREATED = "catalog/product-variant/created"
    const val ORDER_CREATED = "order/order/created"
    const val ON_TOPIC_EVENT_ROUTE = "/on-topic-event"
    const val ON_ORDER_CREATION_EVENT_ROUTE = "/on-order-creation-event"
}
