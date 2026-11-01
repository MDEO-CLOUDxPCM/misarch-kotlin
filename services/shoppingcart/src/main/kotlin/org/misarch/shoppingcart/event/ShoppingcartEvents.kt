package org.misarch.shoppingcart.event

/**
 * Constants for the event topics used by the shopping cart service
 */
object ShoppingcartEvents {
    /**
     * Topic for user creation events (a user has been created)
     */
    const val USER_CREATED = "user/user/created"

    /**
     * Topic for product variant creation events (a product variant has been created)
     */
    const val PRODUCT_VARIANT_CREATED = "catalog/product-variant/created"

    /**
     * Topic for order creation events (an order has been placed)
     */
    const val ORDER_CREATED = "order/order/created"
}
