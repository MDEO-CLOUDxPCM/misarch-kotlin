package org.misarch.order.mechanisms

import kotlinx.coroutines.flow.toList
import org.misarch.order.event.model.CouponDTO
import org.misarch.order.event.model.ProductVariantVersionDTO
import org.misarch.order.event.model.ShipmentMethodDTO
import org.misarch.order.client.model.CalculateShipmentFeesInput
import org.misarch.order.client.model.DiscountDTO
import org.misarch.order.client.model.DiscountsForProductVariantDTO
import org.misarch.order.client.model.FindApplicableDiscountsInput
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

/**
 * Mechanism "local copies" (docs/MECHANISMS.md): createOrder reads Discount and ShipmentMethod from local copies in
 * order's database (tables `LocalCopy*Entity`, migration V2) instead of calling discount (S22) and shipment (S23).
 * Switch: [PROPERTY] (env `MISARCH_LOCAL_COPIES`), default off = unchanged behaviour (the tables then stay empty and
 * are never read).
 *
 * The copies are fed by the existing events (no new topics are published): discount/discount/created|updated,
 * discount/coupon/created, catalog/product/created|updated, catalog/product-variant/created,
 * catalog/product-variant-version/created (weight), shipment/shipment-method/created|archived. The topics order does
 * not subscribe anyway are handled by [LocalCopiesEventController] (only registered with the switch on).
 * Whenever the local copy cannot answer exactly like the remote service would (coupons given, unknown variant, unknown
 * or archived shipment method, unknown version), the remote call is made as before (so errors keep their texts).
 * Deviation: discount's per-user usage limit (maxUsagesPerUser) is not checked locally (discount still enforces it
 * when it validates the order).
 *
 * @property enabled whether the local copies are switched on
 */
@Service
class LocalCopies(
    private val discountRepository: LocalCopyDiscountRepository,
    private val couponRepository: LocalCopyCouponRepository,
    private val productRepository: LocalCopyProductRepository,
    private val productVariantRepository: LocalCopyProductVariantRepository,
    private val productVariantVersionRepository: LocalCopyProductVariantVersionRepository,
    private val shipmentMethodRepository: LocalCopyShipmentMethodRepository,
    @Value(ENABLED_EXPRESSION) val enabled: Boolean
) {
    private val logger = LoggerFactory.getLogger(LocalCopies::class.java)

    /** Copies a created or updated discount. */
    suspend fun observeDiscount(discountDTO: LocalCopyDiscountDTO) {
        if (!enabled) return
        discountRepository.upsertDiscount(
            discountDTO.id,
            discountDTO.discount,
            OffsetDateTime.parse(discountDTO.validFrom),
            OffsetDateTime.parse(discountDTO.validUntil),
            discountDTO.minOrderAmount,
            discountDTO.discountAppliesToProductVariantIds.toTypedArray(),
            discountDTO.discountAppliesToProductIds.toTypedArray(),
            discountDTO.discountAppliesToCategoryIds.toTypedArray()
        )
    }

    /** Copies the discount of a created coupon (after [org.misarch.order.service.CouponService]'s replica). */
    suspend fun observeCoupon(couponDTO: CouponDTO) {
        if (!enabled) return
        couponRepository.createCoupon(couponDTO.id, requireNotNull(couponDTO.discountId) { "Coupon without discountId" })
    }

    /** Copies the categories of a created or updated product. */
    suspend fun observeProduct(productDTO: LocalCopyProductDTO) {
        if (!enabled) return
        productRepository.upsertProduct(productDTO.id, productDTO.categoryIds.toTypedArray())
    }

    /** Copies the product of a created product variant. */
    suspend fun observeProductVariant(productVariantDTO: LocalCopyProductVariantDTO) {
        if (!enabled) return
        productVariantRepository.createProductVariant(productVariantDTO.id, productVariantDTO.productId)
    }

    /** Copies the weight of a created product variant version (after the product variant replica). */
    suspend fun observeProductVariantVersion(productVariantVersionDTO: ProductVariantVersionDTO) {
        if (!enabled) return
        productVariantVersionRepository.createProductVariantVersion(
            productVariantVersionDTO.id,
            requireNotNull(productVariantVersionDTO.weight) { "Product variant version without weight" }
        )
    }

    /** Copies the fees of a created shipment method (after the shipment method replica). */
    suspend fun observeShipmentMethod(shipmentMethodDTO: ShipmentMethodDTO) {
        if (!enabled) return
        shipmentMethodRepository.upsertShipmentMethod(
            shipmentMethodDTO.id,
            requireNotNull(shipmentMethodDTO.baseFees) { "Shipment method without baseFees" },
            requireNotNull(shipmentMethodDTO.feesPerItem) { "Shipment method without feesPerItem" },
            requireNotNull(shipmentMethodDTO.feesPerKg) { "Shipment method without feesPerKg" }
        )
    }

    /** Marks the copy of an archived shipment method. */
    suspend fun observeShipmentMethodArchived(shipmentMethodDTO: LocalCopyArchivedShipmentMethodDTO) {
        if (!enabled) return
        shipmentMethodRepository.archiveShipmentMethod(shipmentMethodDTO.id)
    }

    /**
     * Local `findApplicableDiscounts` (discount's DiscountService.findApplicableDiscounts without the usage limit), or
     * null if it cannot answer (switched off, coupons given, a product variant not in the copy)
     *
     * @param input the request discount would get
     */
    suspend fun applicableDiscounts(input: FindApplicableDiscountsInput): List<DiscountsForProductVariantDTO>? {
        if (!enabled || input.productVariants.any { it.couponIds.isNotEmpty() }) {
            return null
        }
        val ids = input.productVariants.map { it.productVariantId }.distinct()
        val variants = productVariantRepository.findWithCategories(ids.toTypedArray()).toList().associateBy { it.id }
        if (!ids.all { it in variants }) {
            return null
        }
        // only discounts that can apply to one of the items are loaded (array overlap); the exact match per item follows
        val discounts = discountRepository.findApplicable(
            input.orderAmount.toLong(),
            ids.toTypedArray(),
            variants.values.map { it.productId }.distinct().toTypedArray(),
            variants.values.flatMap { it.categoryIds.orEmpty() }.distinct().toTypedArray()
        ).toList()
        return input.productVariants.map { item ->
            val variant = variants.getValue(item.productVariantId)
            val categoryIds = variant.categoryIds.orEmpty().toSet()
            DiscountsForProductVariantDTO(item.productVariantId, discounts.filter { discount ->
                item.productVariantId in discount.appliesVariantIds || variant.productId in discount.appliesProductIds ||
                    discount.appliesCategoryIds.any { it in categoryIds }
            }.map { DiscountDTO(it.id, it.discount) })
        }
    }

    /**
     * Local `calculateShipmentFees` (shipment's ShipmentMethodService), or null if it cannot answer exactly (switched
     * off, a quantity <= 0, a shipment method unknown or archived, a version weight unknown)
     *
     * @param input the request shipment would get
     */
    suspend fun shipmentFees(input: CalculateShipmentFeesInput): Long? {
        if (!enabled || input.items.any { it.quantity <= 0 }) {
            return null
        }
        val methods = shipmentMethodRepository.findAllByIds(input.items.map { it.shipmentMethodId }.distinct().toTypedArray())
            .toList().associateBy { it.id }
        if (input.items.any { methods[it.shipmentMethodId]?.archived != false }) {
            return null
        }
        val weights = productVariantVersionRepository.findAllByIds(
            input.items.map { it.productVariantVersionId }.distinct().toTypedArray()
        ).toList().associate { it.id to it.weight }
        if (input.items.any { it.productVariantVersionId !in weights }) {
            return null
        }
        // shipment: per method group baseFees + quantity * feesPerItem + (weight * feesPerKg).toInt()
        return input.items.groupBy { it.shipmentMethodId }.map { (methodId, items) ->
            val method = methods.getValue(methodId)
            val quantity = items.sumOf { it.quantity }
            val weight = items.sumOf { weights.getValue(it.productVariantVersionId) * it.quantity }
            method.baseFees + quantity * method.feesPerItem + (weight * method.feesPerKg).toInt()
        }.sum().also { logger.debug("Local shipment fees {}", it) }
    }

    companion object {
        /** The switch (env `MISARCH_LOCAL_COPIES`). */
        const val PROPERTY = "misarch.mechanisms.local-copies"

        /** The switch as a condition expression (true with the property or the env name set to true). */
        const val ENABLED_EXPRESSION = "\${$PROPERTY:\${MISARCH_LOCAL_COPIES:false}}"
    }
}

