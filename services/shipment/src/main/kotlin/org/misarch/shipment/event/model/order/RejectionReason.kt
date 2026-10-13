package org.misarch.shipment.event.model.order

/**
 * Describes the reason why an Order was rejected, in case of rejection: REJECTED.
 */
enum class RejectionReason {
    INVALID_ORDER_DATA,
    INVENTORY_RESERVATION_FAILED
}