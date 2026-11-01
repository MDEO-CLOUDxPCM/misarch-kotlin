package org.misarch.inventory.event.model

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonInclude
import java.time.OffsetDateTime
import java.util.UUID

/**
 * DTO of a placed order (`order/order/created`), relayed in the reservation result; `null` values are written.
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
    val createdAt: OffsetDateTime,
    val orderStatus: OrderStatus,
    val placedAt: OffsetDateTime,
    val rejectionReason: RejectionReason?,
    val orderItems: List<OrderItemDTO>,
    val shipmentAddressId: UUID,
    val invoiceAddressId: UUID,
    val compensatableOrderAmount: Long,
    val paymentInformationId: UUID,
    val paymentAuthorization: PaymentAuthorizationDTO?,
    val vatNumber: String?
)

/**
 * DTO of an item of a placed order.
 *
 * @property id unique identifier of the order item
 * @property createdAt the date and time the item was created
 * @property productVariantId the id of the product variant
 * @property productVariantVersionId the id of the product variant version
 * @property taxRateVersionId the id of the tax rate version
 * @property shoppingCartItemId the id of the shopping cart item
 * @property count the quantity
 * @property compensatableAmount what the buyer pays for the item
 * @property shipmentMethodId the id of the shipment method
 * @property discountIds the ids of the discounts applied to the item
 */
data class OrderItemDTO(
    val id: UUID,
    val createdAt: OffsetDateTime,
    val productVariantId: UUID,
    val productVariantVersionId: UUID,
    val taxRateVersionId: UUID,
    val shoppingCartItemId: UUID,
    val count: Long,
    val compensatableAmount: Long,
    val shipmentMethodId: UUID,
    val discountIds: List<UUID>
)

/**
 * DTO of the payment authorization of a placed order; the key `cVC` is accepted as well.
 *
 * @property cvc the CVC/CVV number
 */
data class PaymentAuthorizationDTO(
    @JsonAlias("cVC")
    val cvc: Int
)

/**
 * Status of an order.
 */
enum class OrderStatus {
    PENDING,
    PLACED,
    REJECTED
}

/**
 * The reason why an order was rejected.
 */
enum class RejectionReason {
    INVALID_ORDER_DATA,
    INVENTORY_RESERVATION_FAILED
}
