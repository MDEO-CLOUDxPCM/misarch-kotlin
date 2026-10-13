package org.misarch.shipment.event.model.order

/**
 * Describes if Order is placed, or yet pending. An Order can be rejected during its lifetime.
 */
enum class OrderStatus {
    PENDING,
    PLACED,
    REJECTED
}