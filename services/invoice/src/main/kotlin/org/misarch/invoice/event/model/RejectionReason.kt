package org.misarch.invoice.event.model

/**
 * Reason why an order was rejected
 */
enum class RejectionReason {
    INVALID_ORDER_DATA,
    INVENTORY_RESERVATION_FAILED
}
