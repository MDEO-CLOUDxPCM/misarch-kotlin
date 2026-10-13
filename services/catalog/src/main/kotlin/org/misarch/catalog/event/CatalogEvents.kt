package org.misarch.catalog.event

/**
 * Constants for catalog event topics
 */
object CatalogEvents {
    /**
     * Topic for product creation events
     */
    const val PRODUCT_CREATED = "catalog/product/created"

    /**
     * Topic for product variant creation events
     */
    const val PRODUCT_VARIANT_CREATED = "catalog/product-variant/created"

    /**
     * Topic for product variant version creation events
     */
    const val PRODUCT_VARIANT_VERSION_CREATED = "catalog/product-variant-version/created"

    /**
     * Topic for category creation events
     */
    const val CATEGORY_CREATED = "catalog/category/created"

    /**
     * Topic for product update events
     */
    const val PRODUCT_UPDATED = "catalog/product/updated"

    /**
     * Topic for product variant update events
     */
    const val PRODUCT_VARIANT_UPDATED = "catalog/product-variant/updated"

    /**
     * Topic for tax rate creation events
     */
    const val TAX_RATE_CREATED = "tax/tax-rate/created"

    /**
     * Topic for media creation events
     */
    const val MEDIA_CREATED = "media/media/created"

    /**
     * Name of the pubsub component
     */
    const val PUBSUB_NAME = "pubsub"
}