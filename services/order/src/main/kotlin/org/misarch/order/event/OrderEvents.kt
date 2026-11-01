package org.misarch.order.event

/**
 * Constants for order event topics used in the application
 */
object OrderEvents {
    /**
     * Topic for order creation events (an order has been placed)
     */
    const val ORDER_CREATED = "order/order/created"

    /**
     * Topic for order compensation creation events (order items have been compensated)
     */
    const val ORDER_COMPENSATION_CREATED = "order/order-compensation/created"

    /**
     * Topic for product variant update events (the visibility of a product variant has changed)
     */
    const val PRODUCT_VARIANT_UPDATED = "catalog/product-variant/updated"

    /**
     * Topic for product variant version creation events (a product variant has a new current version)
     */
    const val PRODUCT_VARIANT_VERSION_CREATED = "catalog/product-variant-version/created"

    /**
     * Topic for coupon creation events
     */
    const val COUPON_CREATED = "discount/coupon/created"

    /**
     * Topic for tax rate version creation events (a tax rate has a new current version)
     */
    const val TAX_RATE_VERSION_CREATED = "tax/tax-rate-version/created"

    /**
     * Topic for shipment method creation events
     */
    const val SHIPMENT_METHOD_CREATED = "shipment/shipment-method/created"

    /**
     * Topic for user creation events
     */
    const val USER_CREATED = "user/user/created"

    /**
     * Topic for user address creation events
     */
    const val USER_ADDRESS_CREATED = "address/user-address/created"

    /**
     * Topic for user address archive events
     */
    const val USER_ADDRESS_ARCHIVED = "address/user-address/archived"

    /**
     * Topic for shipment creation failure events (the order items of the shipment are compensated)
     */
    const val SHIPMENT_CREATION_FAILED = "shipment/shipment/creation-failed"
}
