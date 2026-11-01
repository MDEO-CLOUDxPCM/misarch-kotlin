package org.misarch.`return`.event

import io.dapr.Topic
import org.misarch.common.event.CloudEvent
import org.misarch.common.event.PUBSUB_NAME
import org.misarch.`return`.event.model.OrderDTO
import org.misarch.`return`.event.model.ProductVariantVersionDTO
import org.misarch.`return`.event.model.ShipmentDTO
import org.misarch.`return`.event.model.ShipmentStatusUpdatedDTO
import org.misarch.`return`.service.OrderService
import org.misarch.`return`.service.ProductVariantVersionService
import org.misarch.`return`.service.ShipmentService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * Controller for dapr events. Every handler runs in one transaction and is idempotent, so a failed delivery leaves
 * nothing behind and a redelivery succeeds (fix, docs/fixes/return.md; the original's handlers were neither).
 *
 * @param shipmentService the service for [ShipmentDTO]s
 * @param orderService the service for [OrderDTO]s
 * @param productVariantVersionService the service for [ProductVariantVersionDTO]s
 */
@Controller
class EventController(
    private val shipmentService: ShipmentService,
    private val orderService: OrderService,
    private val productVariantVersionService: ProductVariantVersionService
) {

    /**
     * Handles a shipment created event
     *
     * @param cloudEvent the cloud event containing the shipment created
     */
    @Topic(name = ReturnEvents.SHIPMENT_CREATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${ReturnEvents.SHIPMENT_CREATED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onShipmentCreated(
        @RequestBody
        cloudEvent: CloudEvent<ShipmentDTO>
    ) {
        shipmentService.registerShipment(cloudEvent.data)
    }

    /**
     * Handles a shipment status updated event
     *
     * @param cloudEvent the cloud event containing the shipment status updated
     */
    @Topic(name = ReturnEvents.SHIPMENT_STATUS_UPDATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${ReturnEvents.SHIPMENT_STATUS_UPDATED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onShipmentStatusUpdated(
        @RequestBody
        cloudEvent: CloudEvent<ShipmentStatusUpdatedDTO>
    ) {
        shipmentService.updateDeliveredAt(cloudEvent.data)
    }

    /**
     * Handles an order created event
     *
     * @param cloudEvent the cloud event containing the order created
     */
    @Topic(name = ReturnEvents.ORDER_CREATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${ReturnEvents.ORDER_CREATED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onOrderCreated(
        @RequestBody
        cloudEvent: CloudEvent<OrderDTO>
    ) {
        orderService.registerOrder(cloudEvent.data)
    }

    /**
     * Handles a product variant version created event (the return period of the variant version).
     *
     * Fix (docs/fixes/return.md): the original had neither `@Topic` nor `@PostMapping` here, so the topic was never
     * subscribed, `ProductVariantVersionEntity` stayed empty and every `createReturn` failed with
     * "Product variant version not found".
     *
     * @param cloudEvent the cloud event containing the product variant version created
     */
    @Topic(name = ReturnEvents.PRODUCT_VARIANT_VERSION_CREATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${ReturnEvents.PRODUCT_VARIANT_VERSION_CREATED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onProductVariantVersionCreated(
        @RequestBody
        cloudEvent: CloudEvent<ProductVariantVersionDTO>
    ) {
        productVariantVersionService.registerProductVariantVersion(cloudEvent.data)
    }

}
