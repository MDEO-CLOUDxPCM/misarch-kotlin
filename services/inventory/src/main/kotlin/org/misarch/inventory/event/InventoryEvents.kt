package org.misarch.inventory.event

/**
 * Constants for the event topics used by the inventory service
 */
object InventoryEvents {
    /**
     * Topic for product variant creation events (a product variant has been created in the catalog)
     */
    const val PRODUCT_VARIANT_CREATED = "catalog/product-variant/created"

    /**
     * Topic for order creation events (the product items of the order are reserved)
     */
    const val ORDER_CREATED = "order/order/created"

    /**
     * Topic for payment enabled events (the reserved product items go into fulfillment)
     */
    const val PAYMENT_ENABLED = "payment/payment/payment-enabled"

    /**
     * Topic for payment failed events (the reserved product items are released)
     */
    const val PAYMENT_FAILED = "payment/payment/payment-failed"

    /**
     * Topic for shipment creation events (the product items of the order are shipped)
     */
    const val SHIPMENT_CREATED = "shipment/shipment/created"

    /**
     * Topic for shipment status update events (the product items of the order are delivered or lost)
     */
    const val SHIPMENT_STATUS_UPDATED = "shipment/shipment/status-updated"

    /**
     * Topic for failed discount validations of an order (the reserved product items are released)
     */
    const val DISCOUNT_VALIDATION_FAILED = "discount/order/validation-failed"

    /**
     * Topic for successful reservations of all product items of an order
     */
    const val RESERVATION_SUCCEEDED = "inventory/product-item/reservation-succeeded"

    /**
     * Topic for failed reservations of the product items of an order
     */
    const val RESERVATION_FAILED = "inventory/product-item/reservation-failed"
}
