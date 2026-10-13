package org.misarch.shipment.event

/**
 * Constants for shipment event topics used in the application
 */
object ShipmentEvents {
    /**
     * Topic for shipment creation events (a shipment has been created)
     */
    const val SHIPMENT_CREATED = "shipment/shipment/created"

    /**
     * Topic for shipment creation failed events (a shipment creation has failed)
     */
    const val SHIPMENT_CREATION_FAILED = "shipment/shipment/creation-failed"

    /**
     * Topic for shipment status update events (a shipment state has been changed)
     */
    const val SHIPMENT_STATUS_UPDATED = "shipment/shipment/status-updated"

    /**
     * Topic for shipment method creation events (a shipment method has been created)
     */
    const val SHIPMENT_METHOD_CREATED = "shipment/shipment-method/created"

    /**
     * Topic for shipment method archive events (a shipment method has been archived)
     */
    const val SHIPMENT_METHOD_ARCHIVED = "shipment/shipment-method/archived"

    /**
     * Topic for user address creation events (a user address has been created)
     */
    const val USER_ADDRESS_CREATED = "address/user-address/created"

    /**
     * Topic for vendor address creation events (a vendor address has been created)
     */
    const val VENDOR_ADDRESS_CREATED = "address/vendor-address/created"

    /**
     * Topic for product variant version creation events (a product variant version has been created)
     */
    const val PRODUCT_VARIANT_VERSION_CREATED = "catalog/product-variant-version/created"

    /**
     * Topic for payment enabled events (payment has been enabled by the payment service)
     */
    const val PAYMENT_ENABLED = "payment/payment/payment-enabled"

    const val RETURN_CREATED = "return/return/created"

    /**
     * Name of the pubsub component
     */
    const val PUBSUB_NAME = "pubsub"
}