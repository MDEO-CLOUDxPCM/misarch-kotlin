package org.misarch.invoice.event.model

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.UUID

/**
 * DTO of an order (received from discount, republished with the invoice); `null` values are written
 *
 * @property id unique identifier of the order
 * @property userId the id of the user owning the order
 * @property createdAt the date and time the order was created
 * @property orderStatus the status of the order
 * @property placedAt the date and time the order was placed
 * @property rejectionReason the reason why the order was rejected, if it was
 * @property orderItems the items of the order
 * @property shipmentAddressId the id of the address the order is shipped to
 * @property invoiceAddressId the id of the address of the invoice
 * @property compensatableOrderAmount the total compensatable amount of the order
 * @property paymentInformationId the id of the payment information the order is paid with
 * @property paymentAuthorization the optional payment authorization
 * @property vatNumber the optional VAT number
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class OrderDTO(
    val id: UUID,
    val userId: UUID,
    val createdAt: Instant,
    val orderStatus: OrderStatus,
    val placedAt: Instant,
    val rejectionReason: RejectionReason? = null,
    val orderItems: List<OrderItemDTO>,
    val shipmentAddressId: UUID,
    val invoiceAddressId: UUID,
    val compensatableOrderAmount: Long,
    val paymentInformationId: UUID,
    val paymentAuthorization: PaymentAuthorizationDTO? = null,
    val vatNumber: String? = null
)
