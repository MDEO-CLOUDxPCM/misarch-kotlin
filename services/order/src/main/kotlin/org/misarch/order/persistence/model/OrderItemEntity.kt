package org.misarch.order.persistence.model

import org.misarch.common.persistence.BaseEntity
import org.misarch.order.event.model.OrderItemDTO
import org.misarch.order.graphql.model.OrderItem
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Entity for order items. The product variant version and the tax rate version are copied into the item when the
 * order is created.
 *
 * @property orderId the id of the order
 * @property position the position of the item in the order
 * @property createdAt the date and time the item was created
 * @property productVariantId the id of the product variant
 * @property productVariantIsPubliclyVisible whether the product variant was publicly visible
 * @property productVariantVersionId the id of the product variant version
 * @property productVariantVersionPrice the retail price of the product variant version
 * @property productVariantVersionTaxRateId the id of the tax rate of the product variant version
 * @property taxRateVersionId the id of the tax rate version
 * @property taxRateVersionRate the rate of the tax rate version
 * @property taxRateVersionVersion the version number of the tax rate version
 * @property shoppingCartItemId the id of the shopping cart item the item was ordered from
 * @property count the quantity
 * @property compensatableAmount what the buyer pays for the item (discounts applied)
 * @property shipmentMethodId the id of the shipment method
 * @property id unique identifier of the order item
 */
@Table
class OrderItemEntity(
    val orderId: UUID,
    val position: Int,
    val createdAt: OffsetDateTime,
    val productVariantId: UUID,
    val productVariantIsPubliclyVisible: Boolean,
    val productVariantVersionId: UUID,
    val productVariantVersionPrice: Long,
    val productVariantVersionTaxRateId: UUID,
    val taxRateVersionId: UUID,
    val taxRateVersionRate: Double,
    val taxRateVersionVersion: Long,
    val shoppingCartItemId: UUID,
    val count: Long,
    val compensatableAmount: Long,
    val shipmentMethodId: UUID,
    @Id
    override val id: UUID?
) : BaseEntity<OrderItem> {

    override fun toDTO(): OrderItem {
        return OrderItem(
            id = id!!,
            orderId = orderId,
            createdAt = createdAt,
            productVariantId = productVariantId,
            productVariantVersionId = productVariantVersionId,
            taxRateVersionId = taxRateVersionId,
            shoppingCartItemId = shoppingCartItemId,
            count = count.toInt(),
            compensatableAmount = compensatableAmount.toInt(),
            shipmentMethodId = shipmentMethodId
        )
    }

    /**
     * Converts the entity to an event DTO
     *
     * @param discountIds the ids of the discounts applied to the item
     * @return the event DTO
     */
    fun toEventDTO(discountIds: List<UUID>): OrderItemDTO {
        return OrderItemDTO(
            id = id!!,
            createdAt = createdAt.toInstant(),
            productVariantId = productVariantId,
            productVariantVersionId = productVariantVersionId,
            taxRateVersionId = taxRateVersionId,
            shoppingCartItemId = shoppingCartItemId,
            count = count,
            compensatableAmount = compensatableAmount,
            shipmentMethodId = shipmentMethodId,
            discountIds = discountIds
        )
    }

}
