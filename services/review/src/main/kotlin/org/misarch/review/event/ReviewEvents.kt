package org.misarch.review.event

/**
 * Constants for the event topics used by the review service
 */
object ReviewEvents {
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
}
