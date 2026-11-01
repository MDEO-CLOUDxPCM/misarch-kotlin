package org.misarch.wishlist.event

/**
 * Constants for the event topics used by the wishlist service
 */
object WishlistEvents {
    /**
     * Topic for user creation events (a user has been created)
     */
    const val USER_CREATED = "user/user/created"

    /**
     * Topic for product variant creation events (a product variant has been created)
     */
    const val PRODUCT_VARIANT_CREATED = "catalog/product-variant/created"
}
