package org.misarch.order.mechanisms

import io.dapr.Topic
import org.misarch.common.event.CloudEvent
import org.misarch.common.event.PUBSUB_NAME
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * Controller for the dapr events order subscribes to only for its [LocalCopies]: registered only with the switch on,
 * so `/dapr/subscribe` lists these topics only then. The topics order subscribes to anyway (coupon, shipment method
 * and product variant version created) feed the copies from order's `EventController`.
 *
 * @param localCopies the local copies
 */
@Controller
@ConditionalOnExpression(LocalCopies.ENABLED_EXPRESSION)
class LocalCopiesEventController(
    private val localCopies: LocalCopies
) {

    /**
     * Handles a discount created event
     *
     * @param cloudEvent the cloud event containing the created discount
     */
    @Topic(name = DISCOUNT_CREATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/$DISCOUNT_CREATED")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onDiscountCreated(
        @RequestBody
        cloudEvent: CloudEvent<LocalCopyDiscountDTO>
    ) {
        localCopies.observeDiscount(cloudEvent.data)
    }

    /**
     * Handles a discount updated event
     *
     * @param cloudEvent the cloud event containing the updated discount
     */
    @Topic(name = DISCOUNT_UPDATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/$DISCOUNT_UPDATED")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onDiscountUpdated(
        @RequestBody
        cloudEvent: CloudEvent<LocalCopyDiscountDTO>
    ) {
        localCopies.observeDiscount(cloudEvent.data)
    }

    /**
     * Handles a product created event
     *
     * @param cloudEvent the cloud event containing the created product
     */
    @Topic(name = PRODUCT_CREATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/$PRODUCT_CREATED")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onProductCreated(
        @RequestBody
        cloudEvent: CloudEvent<LocalCopyProductDTO>
    ) {
        localCopies.observeProduct(cloudEvent.data)
    }

    /**
     * Handles a product updated event
     *
     * @param cloudEvent the cloud event containing the updated product
     */
    @Topic(name = PRODUCT_UPDATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/$PRODUCT_UPDATED")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onProductUpdated(
        @RequestBody
        cloudEvent: CloudEvent<LocalCopyProductDTO>
    ) {
        localCopies.observeProduct(cloudEvent.data)
    }

    /**
     * Handles a product variant created event
     *
     * @param cloudEvent the cloud event containing the created product variant
     */
    @Topic(name = PRODUCT_VARIANT_CREATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/$PRODUCT_VARIANT_CREATED")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onProductVariantCreated(
        @RequestBody
        cloudEvent: CloudEvent<LocalCopyProductVariantDTO>
    ) {
        localCopies.observeProductVariant(cloudEvent.data)
    }

    /**
     * Handles a shipment method archived event
     *
     * @param cloudEvent the cloud event containing the archived shipment method
     */
    @Topic(name = SHIPMENT_METHOD_ARCHIVED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/$SHIPMENT_METHOD_ARCHIVED")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onShipmentMethodArchived(
        @RequestBody
        cloudEvent: CloudEvent<LocalCopyArchivedShipmentMethodDTO>
    ) {
        localCopies.observeShipmentMethodArchived(cloudEvent.data)
    }

    companion object {
        const val DISCOUNT_CREATED = "discount/discount/created"
        const val DISCOUNT_UPDATED = "discount/discount/updated"
        const val PRODUCT_CREATED = "catalog/product/created"
        const val PRODUCT_UPDATED = "catalog/product/updated"
        const val PRODUCT_VARIANT_CREATED = "catalog/product-variant/created"
        const val SHIPMENT_METHOD_ARCHIVED = "shipment/shipment-method/archived"

        /** Topics order subscribes to only with the local copies on. */
        val TOPICS = listOf(
            DISCOUNT_CREATED, DISCOUNT_UPDATED, PRODUCT_CREATED, PRODUCT_UPDATED, PRODUCT_VARIANT_CREATED,
            SHIPMENT_METHOD_ARCHIVED
        )
    }
}
