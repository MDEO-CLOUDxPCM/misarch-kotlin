package org.misarch.order.persistence.model

import org.misarch.common.persistence.BaseEntity
import org.misarch.order.event.model.OrderDTO
import org.misarch.order.event.model.OrderItemDTO
import org.misarch.order.event.model.PaymentAuthorizationDTO
import org.misarch.order.graphql.model.Order
import org.misarch.order.graphql.model.OrderStatus
import org.misarch.order.graphql.model.RejectionReason
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Entity for orders
 *
 * @property userId the id of the user owning the order
 * @property createdAt the date and time the order was created
 * @property orderStatus the status of the order
 * @property placedAt the date and time the order was placed, null until it is placed
 * @property rejectionReason the reason why the order was rejected, if it was
 * @property shipmentAddressId the id of the address the order is shipped to
 * @property invoiceAddressId the id of the address of the invoice
 * @property compensatableOrderAmount the total compensatable amount (items and shipment fees)
 * @property paymentInformationId the id of the payment information the order is paid with
 * @property vatNumber the optional VAT number
 * @property id unique identifier of the order
 */
@Table
class OrderEntity(
    val userId: UUID,
    val createdAt: OffsetDateTime,
    var orderStatus: OrderStatus,
    var placedAt: OffsetDateTime?,
    var rejectionReason: RejectionReason?,
    val shipmentAddressId: UUID,
    val invoiceAddressId: UUID,
    val compensatableOrderAmount: Long,
    val paymentInformationId: UUID,
    val vatNumber: String?,
    @Id
    override val id: UUID?
) : BaseEntity<Order> {

    override fun toDTO(): Order {
        return Order(
            id = id!!,
            userId = userId,
            createdAt = createdAt,
            orderStatus = orderStatus,
            placedAt = placedAt,
            rejectionReason = rejectionReason,
            invoiceAddressId = invoiceAddressId,
            compensatableOrderAmount = compensatableOrderAmount.toInt(),
            paymentInformationId = paymentInformationId
        )
    }

    /**
     * Converts the placed order to its event DTO
     *
     * @param orderItems the items of the order (with their discounts)
     * @param cvc the optional CVC of the payment authorization
     * @return the event DTO
     */
    fun toEventDTO(orderItems: List<OrderItemDTO>, cvc: Int?): OrderDTO {
        return OrderDTO(
            id = id!!,
            userId = userId,
            createdAt = createdAt,
            orderStatus = orderStatus,
            placedAt = placedAt!!,
            rejectionReason = rejectionReason,
            orderItems = orderItems,
            shipmentAddressId = shipmentAddressId,
            invoiceAddressId = invoiceAddressId,
            compensatableOrderAmount = compensatableOrderAmount,
            paymentInformationId = paymentInformationId,
            paymentAuthorization = cvc?.let { PaymentAuthorizationDTO(it) },
            vatNumber = vatNumber
        )
    }

}
