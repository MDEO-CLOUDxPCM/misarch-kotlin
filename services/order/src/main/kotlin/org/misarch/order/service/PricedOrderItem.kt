package org.misarch.order.service

import org.misarch.order.client.model.DiscountDTO
import org.misarch.order.graphql.input.OrderItemInput
import org.misarch.order.persistence.model.OrderItemEntity
import org.misarch.order.persistence.model.ProductVariantEntity
import org.misarch.order.persistence.model.TaxRateEntity
import java.time.OffsetDateTime
import java.util.UUID

/**
 * An order item of an order that is being created, with its price
 *
 * @property input the input ordering the item
 * @property productVariant the ordered product variant (its current version is ordered)
 * @property taxRate the tax rate of the product variant version (its current version is used)
 * @property count the quantity (from the shopping cart)
 * @property discounts the applicable discounts
 * @property compensatableAmount what the buyer pays for the item
 */
class PricedOrderItem(
    val input: OrderItemInput,
    val productVariant: ProductVariantEntity,
    val taxRate: TaxRateEntity,
    val count: Long,
    val discounts: List<DiscountDTO>,
    val compensatableAmount: Long
) {

    /**
     * The entity of the item
     *
     * @param orderId the id of the order
     * @param position the position of the item in the order
     * @param createdAt the creation time of the order
     */
    fun toEntity(orderId: UUID, position: Int, createdAt: OffsetDateTime): OrderItemEntity {
        return OrderItemEntity(
            orderId = orderId,
            position = position,
            createdAt = createdAt,
            productVariantId = productVariant.id,
            productVariantIsPubliclyVisible = productVariant.isPubliclyVisible,
            productVariantVersionId = productVariant.currentVersionId,
            productVariantVersionPrice = productVariant.currentVersionPrice,
            productVariantVersionTaxRateId = productVariant.currentVersionTaxRateId,
            taxRateVersionId = taxRate.currentVersionId,
            taxRateVersionRate = taxRate.currentVersionRate,
            taxRateVersionVersion = taxRate.currentVersionVersion,
            shoppingCartItemId = input.shoppingCartItemId,
            count = count,
            compensatableAmount = compensatableAmount,
            shipmentMethodId = input.shipmentMethodId,
            id = null
        )
    }

}
