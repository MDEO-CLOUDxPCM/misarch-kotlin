package org.misarch.shipment.event.model.order

import java.util.UUID

/**
 * DTO of an order of a user.
 *
 * @property id Order UUID.
 * @property userId UUID of user connected with Order.
 * @property createdAt Timestamp when Order was created.
 * @property orderStatus The status of the Order.
 * @property placedAt Timestamp of Order placement.
 * @property rejectionReason The rejection reason if status of the Order is REJECTED.
 * @property orderItems List of OrderItems associated with the Order.
 * @property shipmentAddressId UUID of shipment address associated with the Order.
 * @property invoiceAddressId UUID of invoice address associated with the Order.
 * @property compensatableOrderAmount Total compensatable amount of order.
 * @property paymentInformationId UUID of payment information that the order should be processed with.
 * @property paymentAuthorization Optional payment authorization information.
 * @property vatNumber VAT number of the user.
 */
data class OrderDTO(
    val id: UUID,
    val userId: UUID,
    val createdAt: String,
    val orderStatus: OrderStatus,
    val placedAt: String,
    val rejectionReason: RejectionReason?,
    val orderItems: List<OrderItemDTO>,
    val shipmentAddressId: UUID,
    val invoiceAddressId: UUID,
    val compensatableOrderAmount: Long,
    val paymentInformationId: UUID,
    val paymentAuthorization: PaymentAuthorization?,
    val vatNumber: String?
)