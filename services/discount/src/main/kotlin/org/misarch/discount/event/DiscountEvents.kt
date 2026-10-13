package org.misarch.discount.event

/**
 * Constants for discount event topics used in the application
 */
object DiscountEvents {
    /**
     * Topic for discount creation events (a discount has been created)
     */
    const val DISCOUNT_CREATED = "discount/discount/created"

    /**
     * Topic for coupon creation events (a coupon has been created)
     */
    const val COUPON_CREATED = "discount/coupon/created"

    /**
     * Topic for discount updated events (a discount has been updated)
     */
    const val DISCOUNT_UPDATED = "discount/discount/updated"

    /**
     * Topic for coupon updated events (a coupon has been updated)
     */
    const val COUPON_UPDATED = "discount/coupon/updated"

    /**
     * Topic for order validation succeeded events (an order has been successfully validated)
     */
    const val VALIDATION_SUCCEEDED = "discount/order/validation-succeeded"

    /**
     * Topic for order validation failed events (an order has failed validation)
     */
    const val VALIDATION_FAILED = "discount/order/validation-failed"

    /**
     * Topic for order item reservation succeeded events by the inventory service
     */
    const val INVENTORY_RESERVATION_SUCCEEDED = "inventory/product-item/reservation-succeeded"

    /**
     * Topic for user creation events (a user has been created)
     */
    const val USER_CREATED = "user/user/created"

    /**
     * Topic for product creation events (a product has been created)
     */
    const val PRODUCT_CREATED = "catalog/product/created"

    /**
     * Topic for product variant creation events (a product variant has been created)
     */
    const val PRODUCT_VARIANT_CREATED = "catalog/product-variant/created"

    /**
     * Topic for category creation events (a category has been created)
     */
    const val CATEGORY_CREATED = "catalog/category/created"

    /**
     * Name of the pubsub component
     */
    const val PUBSUB_NAME = "pubsub"
}
