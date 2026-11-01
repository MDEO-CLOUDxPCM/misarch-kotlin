package org.misarch.discount.event.model

/**
 * Describes the reason why an Order was rejected, in case of rejection: REJECTED.
 */
enum class RejectionReason {
    INVALID_ORDER_DATA,
    INVENTORY_RESERVATION_FAILED
}
