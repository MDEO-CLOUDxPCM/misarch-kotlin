package org.misarch.inventory.event

import io.dapr.Topic
import org.misarch.common.event.CloudEvent
import org.misarch.common.event.PUBSUB_NAME
import org.misarch.inventory.event.model.OrderDTO
import org.misarch.inventory.event.model.OrderEventDTO
import org.misarch.inventory.event.model.ProductVariantDTO
import org.misarch.inventory.event.model.ShipmentDTO
import org.misarch.inventory.service.ProductItemService
import org.misarch.inventory.service.ProductVariantService
import org.misarch.inventory.service.ReservationService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * Controller for dapr events
 *
 * @property productVariantService registers product variants
 * @property productItemService updates the status of the product items of an order
 * @property reservationService reserves the product items of an order
 */
@Controller
class EventController(
    private val productVariantService: ProductVariantService,
    private val productItemService: ProductItemService,
    private val reservationService: ReservationService,
) {

    @Topic(name = InventoryEvents.PRODUCT_VARIANT_CREATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${InventoryEvents.PRODUCT_VARIANT_CREATED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onProductVariantCreated(@RequestBody cloudEvent: CloudEvent<ProductVariantDTO>) {
        productVariantService.registerProductVariant(cloudEvent.data)
    }

    @Topic(name = InventoryEvents.ORDER_CREATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${InventoryEvents.ORDER_CREATED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onOrderCreated(@RequestBody cloudEvent: CloudEvent<OrderDTO>) {
        reservationService.reserveProductItemsOfOrder(cloudEvent.data)
    }

    @Topic(name = InventoryEvents.PAYMENT_ENABLED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${InventoryEvents.PAYMENT_ENABLED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onPaymentEnabled(@RequestBody cloudEvent: CloudEvent<OrderEventDTO>) {
        productItemService.markProductItemsOfOrderInFulfillment(cloudEvent.data.order.id)
    }

    @Topic(name = InventoryEvents.PAYMENT_FAILED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${InventoryEvents.PAYMENT_FAILED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onPaymentFailed(@RequestBody cloudEvent: CloudEvent<OrderEventDTO>) {
        productItemService.releaseProductItemsOfOrder(cloudEvent.data.order.id)
    }

    @Topic(name = InventoryEvents.SHIPMENT_CREATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${InventoryEvents.SHIPMENT_CREATED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onShipmentCreated(@RequestBody cloudEvent: CloudEvent<ShipmentDTO>) {
        productItemService.markProductItemsOfShipmentShipped(cloudEvent.data)
    }

    @Topic(name = InventoryEvents.SHIPMENT_STATUS_UPDATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${InventoryEvents.SHIPMENT_STATUS_UPDATED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onShipmentStatusUpdated(@RequestBody cloudEvent: CloudEvent<ShipmentDTO>) {
        productItemService.updateProductItemsOfShipment(cloudEvent.data)
    }

    @Topic(name = InventoryEvents.DISCOUNT_VALIDATION_FAILED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${InventoryEvents.DISCOUNT_VALIDATION_FAILED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onDiscountValidationFailed(@RequestBody cloudEvent: CloudEvent<OrderEventDTO>) {
        productItemService.releaseProductItemsOfOrder(cloudEvent.data.order.id)
    }
}
