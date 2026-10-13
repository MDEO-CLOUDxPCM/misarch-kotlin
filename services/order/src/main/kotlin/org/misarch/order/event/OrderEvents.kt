package org.misarch.order.event

/**
 * Topics and routes of the order service (http_event_service.rs).
 */
object OrderEvents {
    const val PUBSUB_NAME = "pubsub"

    // published
    const val ORDER_CREATED = "order/order/created"
    const val ORDER_COMPENSATION_CREATED = "order/order-compensation/created"

    // consumed
    const val PRODUCT_VARIANT_UPDATED = "catalog/product-variant/updated"
    const val PRODUCT_VARIANT_VERSION_CREATED = "catalog/product-variant-version/created"
    const val COUPON_CREATED = "discount/coupon/created"
    const val TAX_RATE_VERSION_CREATED = "tax/tax-rate-version/created"
    const val SHIPMENT_METHOD_CREATED = "shipment/shipment-method/created"
    const val USER_CREATED = "user/user/created"
    const val USER_ADDRESS_CREATED = "address/user-address/created"
    const val USER_ADDRESS_ARCHIVED = "address/user-address/archived"
    const val SHIPMENT_CREATION_FAILED = "shipment/shipment/creation-failed"

    const val ROUTE_ID_CREATION = "/on-id-creation-event"
    const val ROUTE_PRODUCT_VARIANT_VERSION_CREATION = "/on-product-variant-version-creation-event"
    const val ROUTE_PRODUCT_VARIANT_UPDATED = "/on-product-variant-updated-event"
    const val ROUTE_TAX_RATE_VERSION_CREATION = "/on-tax-rate-version-creation-event"
    const val ROUTE_USER_ADDRESS_CREATION = "/on-user-address-creation-event"
    const val ROUTE_USER_ADDRESS_ARCHIVED = "/on-user-address-archived-event"
    const val ROUTE_SHIPMENT_CREATION_FAILED = "/on-shipment-creation-failed-event"

    /**
     * `list_topic_subscriptions` (`GET /dapr/subscribe`), in the original's order. FIXED (docs/fixes/order.md): the
     * shipment creation-failed route, which the original served but did not subscribe, is subscribed (last entry).
     */
    val SUBSCRIPTIONS: List<Pair<String, String>> = listOf(
        PRODUCT_VARIANT_UPDATED to ROUTE_PRODUCT_VARIANT_UPDATED,
        PRODUCT_VARIANT_VERSION_CREATED to ROUTE_PRODUCT_VARIANT_VERSION_CREATION,
        COUPON_CREATED to ROUTE_ID_CREATION,
        TAX_RATE_VERSION_CREATED to ROUTE_TAX_RATE_VERSION_CREATION,
        SHIPMENT_METHOD_CREATED to ROUTE_ID_CREATION,
        USER_CREATED to ROUTE_ID_CREATION,
        USER_ADDRESS_CREATED to ROUTE_USER_ADDRESS_CREATION,
        USER_ADDRESS_ARCHIVED to ROUTE_USER_ADDRESS_ARCHIVED,
        SHIPMENT_CREATION_FAILED to ROUTE_SHIPMENT_CREATION_FAILED,
    )
}
