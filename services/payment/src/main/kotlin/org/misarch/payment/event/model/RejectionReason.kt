package org.misarch.payment.event.model

/**
 * The reason why an order was rejected.
 */
enum class RejectionReason {
    INVALID_ORDER_DATA,
    INVENTORY_RESERVATION_FAILED
}
