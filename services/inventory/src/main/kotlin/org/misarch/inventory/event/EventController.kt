package org.misarch.inventory.event

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.misarch.common.event.EventPublisher
import org.misarch.common.nest.ClassValidator
import org.misarch.common.nest.DaprSubscriptions
import org.misarch.common.nest.DetachedTasks
import org.misarch.common.nest.Js
import org.misarch.common.nest.JsError
import org.misarch.common.nest.NestRest
import org.misarch.inventory.persistence.ProductItemRow
import org.misarch.inventory.service.InventoryService
import org.misarch.inventory.service.ProductItemStatuses
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

object InventoryEvents {
    const val PUBSUB = "pubsub"
    const val RESERVATION_SUCCEEDED = "inventory/product-item/reservation-succeeded"
    const val RESERVATION_FAILED = "inventory/product-item/reservation-failed"

    /** `GET /dapr/subscribe` of the original, verbatim (including the `pubsubname` key of five entries). */
    const val SUBSCRIPTIONS = """[{"pubsubName":"pubsub","topic":"catalog/product-variant/created","route":"product-variant-created"},""" +
        """{"pubsubName":"pubsub","topic":"order/order/created","route":"order-created"},""" +
        """{"pubsubname":"pubsub","topic":"payment/payment/payment-enabled","route":"payment-enabled"},""" +
        """{"pubsubname":"pubsub","topic":"payment/payment/payment-failed","route":"payment-failed"},""" +
        """{"pubsubname":"pubsub","topic":"shipment/shipment/status-updated","route":"shipment-status-updated"},""" +
        """{"pubsubname":"pubsub","topic":"shipment/shipment/created","route":"shipment-created"},""" +
        """{"pubsubname":"pubsub","topic":"discount/order/validation-failed","route":"discount-validation-failed"}]"""
}

@Configuration(proxyBeanMethods = false)
class InventoryEventConfiguration {
    @Bean
    fun daprSubscriptions() = DaprSubscriptions(InventoryEvents.SUBSCRIPTIONS)
}

/**
 * Publishes like the original `EventPublisherService`: fire-and-forget, a failed publish is only logged.
 */
@org.springframework.stereotype.Component
class InventoryEventPublisher(private val eventPublisher: EventPublisher, private val detached: DetachedTasks) {
    private val logger = LoggerFactory.getLogger(InventoryEventPublisher::class.java)

    fun publish(topic: String, payload: ObjectNode) {
        detached.launch("publish $topic") {
            try {
                eventPublisher.publishEvent(InventoryEvents.PUBSUB, topic, payload)
                logger.info("Published event to topic \"{}\"", topic)
            } catch (e: Exception) {
                logger.error("Error publishing event to topic \"{}\": {}", topic, e.message)
            }
        }
    }
}

/**
 * Dapr subscription routes of the original `EventController` (docs/specs/inventory.md §5.2). All answer 201 like a
 * NestJS `@Post` handler; validation failures 400, JS errors 500.
 */
@RestController
class EventController(
    private val inventoryService: InventoryService,
    private val publisher: InventoryEventPublisher,
    private val detached: DetachedTasks,
) {
    private val logger = LoggerFactory.getLogger(EventController::class.java)

    private fun data(body: String?): JsonNode? = NestRest.parseBody(body).get("data")

    /** `order.id` as the Mongo filter value: a string, or null for null/undefined. */
    private fun idOf(order: JsonNode?): String? {
        val id = order?.get("id") ?: return null
        return if (id.isNull) null else Js.toJsString(id)
    }

    @PostMapping("/product-variant-created")
    suspend fun productVariantCreated(@RequestBody(required = false) body: String?): ResponseEntity<String> = NestRest.handle {
        val dto = data(body)
        if (dto == null || dto.isNull) {
            throw JsError("Cannot destructure property 'id' of 'dto' as it is ${if (dto == null) "undefined" else "null"}.")
        }
        val idNode = dto.get("id")
        logger.info("Received create event for product variant with id: {}", idNode)
        detached.launch("create product variant partial") {
            val id = when {
                idNode == null -> UUID.randomUUID().toString() // Mongoose default for an undefined _id
                idNode.isNull -> throw JsError("ProductVariantPartial validation failed: _id: Path `_id` is required.")
                idNode.isObject || idNode.isArray -> throw JsError("Cast to string failed for value \"$idNode\" at path \"_id\"")
                else -> Js.toJsString(idNode)
            }
            inventoryService.createPartial(id)
        }
        NestRest.created()
    }

    @PostMapping("/order-created")
    suspend fun orderCreated(@RequestBody(required = false) body: String?): ResponseEntity<String> = NestRest.handle {
        val order = data(body)
        ClassValidator.validateOrThrow(EventDtos.ORDER, order)
        order!!
        val orderId = order.get("id").asString()
        logger.info("Received create event for order with id: {}", orderId)
        try {
            val items = order.get("orderItems").toList()
            val results = coroutineScope {
                items.map { item ->
                    async {
                        val productVariantId = item.get("productVariantId").asString()
                        try {
                            inventoryService.reserveProductItemBatch(productVariantId, item.get("count").asLong(), orderId)
                            productVariantId to true
                        } catch (e: Throwable) {
                            logger.error("Error reserving product item with productVariantId \"{}\": {}", productVariantId, e.message)
                            productVariantId to false
                        }
                    }
                }.awaitAll()
            }
            val failed = results.filterNot { it.second }.map { it.first }
            if (failed.isNotEmpty()) {
                logger.error("Failed to reserve product items for order with id: {}", orderId)
                val payload = Js.nodes.objectNode()
                payload.set("order", order)
                payload.set("failedProductVariantIds", Js.nodes.arrayNode().apply { failed.forEach { add(it) } })
                publisher.publish(InventoryEvents.RESERVATION_FAILED, payload)
                val released = inventoryService.releaseProductItemBatch(orderId)
                return@handle NestRest.created(released.map(::documentJson))
            }
            val payload = Js.nodes.objectNode()
            payload.set("order", order)
            publisher.publish(InventoryEvents.RESERVATION_SUCCEEDED, payload)
            NestRest.created()
        } catch (e: Throwable) {
            logger.error("Error processing order event: {}", e.message)
            // the original *returns* the exception object (status 201, JSON.stringify of the exception)
            NestRest.created(
                linkedMapOf(
                    "response" to linkedMapOf("message" to e.message),
                    "status" to 422,
                    "options" to emptyMap<String, Any>(),
                    "message" to "Unprocessable Entity Exception",
                    "name" to "UnprocessableEntityException",
                )
            )
        }
    }

    @PostMapping("/payment-enabled")
    suspend fun paymentEnabled(@RequestBody(required = false) body: String?): ResponseEntity<String> = NestRest.handle {
        val dto = data(body)
        ClassValidator.validateOrThrow(EventDtos.PAYMENT_EVENT, dto)
        inventoryService.updateOrderProductItemsStatus(idOf(dto!!.get("order")), ProductItemStatuses.IN_FULFILLMENT)
        NestRest.created()
    }

    @PostMapping("/payment-failed")
    suspend fun paymentFailed(@RequestBody(required = false) body: String?): ResponseEntity<String> = NestRest.handle {
        val dto = data(body)
        ClassValidator.validateOrThrow(EventDtos.PAYMENT_EVENT, dto)
        val orderId = idOf(dto!!.get("order"))
        logger.info("Received payment failed for order with id: {}", orderId)
        detached.launch("release after payment failed") { inventoryService.releaseProductItemBatch(orderId) }
        NestRest.created()
    }

    @PostMapping("/shipment-created")
    suspend fun shipmentCreated(@RequestBody(required = false) body: String?): ResponseEntity<String> = NestRest.handle {
        val dto = data(body)
        ClassValidator.validateOrThrow(EventDtos.SHIPMENT, dto)
        val orderId = dto!!.get("orderId")?.takeIf { it.isString && it.asString().isNotEmpty() }?.asString()
        // create is for the return process when there is no order
        if (orderId != null) inventoryService.updateOrderProductItemsStatus(orderId, ProductItemStatuses.SHIPPED)
        NestRest.created()
    }

    @PostMapping("/shipment-status-updated")
    suspend fun shipmentStatusUpdated(@RequestBody(required = false) body: String?): ResponseEntity<String> = NestRest.handle {
        val dto = data(body)
        ClassValidator.validateOrThrow(EventDtos.SHIPMENT, dto)
        val orderId = dto!!.get("orderId")?.takeIf { it.isString && it.asString().isNotEmpty() }?.asString()
        if (orderId != null) {
            when (dto.get("status").asString()) {
                "DELIVERED" -> inventoryService.updateOrderProductItemsStatus(orderId, ProductItemStatuses.DELIVERED)
                "FAILED" -> inventoryService.updateOrderProductItemsStatus(orderId, ProductItemStatuses.LOST)
            }
        }
        NestRest.created()
    }

    @PostMapping("/discount-validation-failed")
    suspend fun discountValidationFailed(@RequestBody(required = false) body: String?): ResponseEntity<String> = NestRest.handle {
        val dto = data(body)
        if (dto == null || dto.isNull) {
            throw JsError("Cannot destructure property 'order' of 'discountDto' as it is ${if (dto == null) "undefined" else "null"}.")
        }
        val order = dto.get("order")
        if (order == null || order.isNull) {
            throw JsError("Cannot read properties of ${if (order == null) "undefined" else "null"} (reading 'id')")
        }
        val orderId = idOf(order)
        logger.info("Received discount validation failed for order with id: {}", orderId)
        detached.launch("release after discount validation failed") { inventoryService.releaseProductItemBatch(orderId) }
        NestRest.created()
    }

    /** A product item document as Mongoose serializes it (`toJSON` with the `id` virtual). */
    private fun documentJson(row: ProductItemRow): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        put("_id", row.id)
        if (row.productVariant != null) put("productVariant", row.productVariant)
        put("inventoryStatus", row.inventoryStatus)
        put("orderId", row.orderId)
        put("id", row.id)
    }
}
