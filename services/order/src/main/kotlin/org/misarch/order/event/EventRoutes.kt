package org.misarch.order.event

import org.misarch.order.remote.elementList
import org.misarch.common.rust.RustUuid
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBodyOrNull
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait
import org.springframework.web.reactive.function.server.coRouter
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Dapr endpoints of the original (axum router in main.rs, handlers in http_event_service.rs), as functional routes:
 * functional routes take precedence over annotated controllers, so `GET /dapr/subscribe` answers the original's exact
 * list instead of the Dapr SDK's `@Topic` scan (the original subscribes three topics on one route and serves one
 * route without subscribing it, which `@Topic` cannot express).
 *
 * Handler contract (axum `Json<Event<T>>`, docs/specs/order.md §7):
 * - Content-Type not `application/json` / `application/<subtype>+json` → 415,
 * - body not valid JSON → 400, JSON not matching `{topic: String, data: T}` → 422 (axum rejections, plain text),
 * - unexpected topic or a failing handler → 500 with empty body (Dapr redelivers),
 * - success → 200 `{"status":0}`.
 */
@Configuration(proxyBeanMethods = false)
class EventRoutes(private val handlers: EventHandlers) {

    private val logger = LoggerFactory.getLogger(EventRoutes::class.java)

    @Bean
    fun orderDaprRoutes() = coRouter {
        GET("/dapr/subscribe") {
            ok().contentType(MediaType.APPLICATION_JSON).bodyValueAndAwait(SUBSCRIBE_JSON)
        }
        // axum `/health`: 200 with an empty body (the actuator's web endpoints are switched off in application.yaml)
        GET("/health") {
            ok().buildAndAwait()
        }
        POST(OrderEvents.ROUTE_ID_CREATION) { request ->
            handle(request, { EventData.Id(it.uuid("id")) }) { topic, data -> handlers.onIdCreation(topic, data.id) }
        }
        POST(OrderEvents.ROUTE_PRODUCT_VARIANT_VERSION_CREATION) { request ->
            handle(request, {
                EventData.ProductVariantVersion(it.uuid("id"), it.u32("retailPrice"), it.uuid("taxRateId"), it.uuid("productVariantId"))
            }) { topic, data -> handlers.onProductVariantVersionCreation(topic, data) }
        }
        POST(OrderEvents.ROUTE_PRODUCT_VARIANT_UPDATED) { request ->
            handle(request, { EventData.ProductVariantUpdate(it.uuid("id"), it.visibility()) }) { topic, data ->
                handlers.onProductVariantUpdate(topic, data)
            }
        }
        POST(OrderEvents.ROUTE_TAX_RATE_VERSION_CREATION) { request ->
            handle(request, {
                EventData.TaxRateVersion(it.uuid("id"), it.f64("rate"), it.u32("version"), it.uuid("taxRateId"))
            }) { topic, data -> handlers.onTaxRateVersionCreation(topic, data) }
        }
        POST(OrderEvents.ROUTE_USER_ADDRESS_CREATION) { request ->
            handle(request, { EventData.UserAddress(it.uuid("id"), it.uuid("userId")) }) { topic, data ->
                handlers.onUserAddressCreation(topic, data)
            }
        }
        POST(OrderEvents.ROUTE_USER_ADDRESS_ARCHIVED) { request ->
            handle(request, { EventData.UserAddress(it.uuid("id"), it.uuid("userId")) }) { topic, data ->
                handlers.onUserAddressArchived(topic, data)
            }
        }
        POST(OrderEvents.ROUTE_SHIPMENT_CREATION_FAILED) { request ->
            handle(request, { EventData.ShipmentFailed(it.optionalUuid("orderId"), it.uuidList("orderItemIds")) }) { topic, data ->
                handlers.onShipmentCreationFailed(topic, data)
            }
        }
    }

    private suspend fun <T> handle(
        request: ServerRequest,
        parseData: (JsonNode) -> T,
        handler: suspend (topic: String, data: T) -> Unit,
    ): ServerResponse {
        val contentType = request.headers().contentType().orElse(null)
        if (contentType == null || contentType.type != "application" ||
            !(contentType.subtype == "json" || contentType.subtype.endsWith("+json"))
        ) {
            return plain(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Expected request with `Content-Type: application/json`")
        }
        val body = request.awaitBodyOrNull<ByteArray>() ?: ByteArray(0)
        val json = try {
            MAPPER.readTree(body)
        } catch (e: Exception) {
            null
        }
        if (json == null || json.isMissingNode) {
            return plain(HttpStatus.BAD_REQUEST, "Failed to parse the request body as JSON")
        }
        val (topic, data) = try {
            if (!json.isObject) throw InvalidEventData("invalid type: expected struct Event")
            json.string("topic") to parseData(json.field("data"))
        } catch (e: InvalidEventData) {
            return plain(HttpStatus.UNPROCESSABLE_CONTENT, "Failed to deserialize the JSON body into the target type: ${e.message}")
        }
        logger.info("Event {} {}", topic, json.get("data"))
        return try {
            handler(topic, data)
            ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValueAndAwait(SUCCESS_JSON)
        } catch (e: Exception) {
            logger.info("Event handler failed: {}", e.toString())
            ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).buildAndAwait()
        }
    }

    private suspend fun plain(status: HttpStatus, message: String): ServerResponse =
        ServerResponse.status(status).contentType(MediaType.TEXT_PLAIN).bodyValueAndAwait(message)

    companion object {
        private val MAPPER = JsonMapper.builder().build()

        /** `Json(TopicEventResponse::default())`. */
        const val SUCCESS_JSON = """{"status":0}"""

        /** `list_topic_subscriptions`. */
        val SUBSCRIBE_JSON: String = MAPPER.writeValueAsString(OrderEvents.SUBSCRIPTIONS.map { (topic, route) ->
            linkedMapOf("pubsubName" to OrderEvents.PUBSUB_NAME, "topic" to topic, "route" to route)
        })
    }
}

/** Event payloads (`data`) of the original's handlers. */
sealed interface EventData {
    data class Id(val id: UUID) : EventData
    data class ProductVariantVersion(val id: UUID, val retailPrice: Long, val taxRateId: UUID, val productVariantId: UUID) : EventData
    data class ProductVariantUpdate(val id: UUID, val isPubliclyVisible: Boolean) : EventData
    data class TaxRateVersion(val id: UUID, val rate: Double, val version: Long, val taxRateId: UUID) : EventData
    data class UserAddress(val id: UUID, val userId: UUID) : EventData
    /** `orderId` is null for a failed return shipment. */
    data class ShipmentFailed(val orderId: UUID?, val orderItemIds: List<UUID>) : EventData
}

/** serde deserialization failure (→ 422). */
class InvalidEventData(message: String) : RuntimeException(message)

private fun JsonNode.field(name: String): JsonNode {
    if (!isObject) throw InvalidEventData("invalid type: expected a struct")
    return get(name) ?: throw InvalidEventData("missing field `$name`")
}

private fun JsonNode.string(name: String): String =
    field(name).let { if (it.isString) it.asString() else throw InvalidEventData("$name: invalid type: $it, expected a string") }

private fun JsonNode.uuid(name: String): UUID =
    field(name).let { node ->
        if (!node.isString) throw InvalidEventData("$name: invalid type: $node, expected a UUID")
        RustUuid.tryParse(node.asString()) ?: throw InvalidEventData("$name: invalid UUID ${node.asString()}")
    }

private fun JsonNode.uuidList(name: String): List<UUID> =
    field(name).let { node ->
        if (!node.isArray) throw InvalidEventData("$name: invalid type: $node, expected a sequence")
        node.elementList().map { element ->
            if (!element.isString) throw InvalidEventData("$name: invalid type: $element, expected a UUID")
            RustUuid.tryParse(element.asString()) ?: throw InvalidEventData("$name: invalid UUID ${element.asString()}")
        }
    }

/** A UUID that may be missing or null. */
private fun JsonNode.optionalUuid(name: String): UUID? {
    if (!isObject) throw InvalidEventData("invalid type: expected a struct")
    val node = get(name)
    if (node == null || node.isNull) return null
    return uuid(name)
}

/**
 * The visibility of `catalog/product-variant/updated` (FIXED, docs/fixes/order.md): catalog publishes the boolean
 * `publiclyVisible` (Jackson bean naming of `isPubliclyVisible`); the documented key `isPubliclyVisible` is accepted
 * too, as a boolean or as the string `"true"`/`"false"`.
 */
private fun JsonNode.visibility(): Boolean {
    if (!isObject) throw InvalidEventData("invalid type: expected a struct")
    val node = get("publiclyVisible") ?: get("isPubliclyVisible")
        ?: throw InvalidEventData("missing field `publiclyVisible`")
    return when {
        node.isBoolean -> node.booleanValue()
        node.isString && node.asString() in setOf("true", "false") -> node.asString() == "true"
        else -> throw InvalidEventData("publiclyVisible: invalid type: $node, expected a boolean")
    }
}

/** serde `u32`: integers 0..=4294967295 only. */
private fun JsonNode.u32(name: String): Long =
    field(name).let { node ->
        if (node.isIntegralNumber && node.canConvertToLong() && node.longValue() in 0..0xFFFF_FFFFL) node.longValue()
        else throw InvalidEventData("$name: invalid value: $node, expected u32")
    }

/** serde `f64`: any number. */
private fun JsonNode.f64(name: String): Double =
    field(name).let { node ->
        if (node.isNumber) node.doubleValue() else throw InvalidEventData("$name: invalid type: $node, expected f64")
    }
