package org.misarch.order.mechanisms

import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Repository for [LocalCopyDiscountEntity]s
 */
interface LocalCopyDiscountRepository : CoroutineCrudRepository<LocalCopyDiscountEntity, UUID> {

    /** Creates or replaces the copy of a discount. */
    @Modifying
    @Query(
        "INSERT INTO LocalCopyDiscountEntity (id, discount, validFrom, validUntil, minOrderAmount, appliesVariantIds, " +
            "appliesProductIds, appliesCategoryIds) VALUES (:id, :discount, :validFrom, :validUntil, :minOrderAmount, " +
            ":variantIds, :productIds, :categoryIds) ON CONFLICT (id) DO UPDATE SET discount = :discount, " +
            "validFrom = :validFrom, validUntil = :validUntil, minOrderAmount = :minOrderAmount, " +
            "appliesVariantIds = :variantIds, appliesProductIds = :productIds, appliesCategoryIds = :categoryIds"
    )
    suspend fun upsertDiscount(
        id: UUID,
        discount: Double,
        validFrom: OffsetDateTime,
        validUntil: OffsetDateTime,
        minOrderAmount: Long?,
        variantIds: Array<UUID>,
        productIds: Array<UUID>,
        categoryIds: Array<UUID>
    )

    /**
     * The valid discounts without a coupon whose minimum order amount is reached and that apply to one of the product
     * variants, products or categories (array overlap; the exact match per product variant is the caller's)
     */
    @Query(
        "SELECT d.* FROM LocalCopyDiscountEntity d WHERE d.validFrom <= now() AND d.validUntil >= now() " +
            "AND (d.minOrderAmount IS NULL OR d.minOrderAmount <= :orderAmount) " +
            "AND (d.appliesVariantIds && :variantIds OR d.appliesProductIds && :productIds " +
            "OR d.appliesCategoryIds && :categoryIds) " +
            "AND NOT EXISTS (SELECT 1 FROM LocalCopyCouponEntity c WHERE c.discountId = d.id)"
    )
    fun findApplicable(
        orderAmount: Long,
        variantIds: Array<UUID>,
        productIds: Array<UUID>,
        categoryIds: Array<UUID>
    ): Flow<LocalCopyDiscountEntity>
}

/**
 * Repository for [LocalCopyCouponEntity]s
 */
interface LocalCopyCouponRepository : CoroutineCrudRepository<LocalCopyCouponEntity, UUID> {

    /** Creates the copy of a coupon; an existing one is kept. */
    @Modifying
    @Query("INSERT INTO LocalCopyCouponEntity (id, discountId) VALUES (:id, :discountId) ON CONFLICT DO NOTHING")
    suspend fun createCoupon(id: UUID, discountId: UUID)
}

/**
 * Repository for [LocalCopyProductEntity]s
 */
interface LocalCopyProductRepository : CoroutineCrudRepository<LocalCopyProductEntity, UUID> {

    /** Creates or replaces the copy of a product. */
    @Modifying
    @Query(
        "INSERT INTO LocalCopyProductEntity (id, categoryIds) VALUES (:id, :categoryIds) " +
            "ON CONFLICT (id) DO UPDATE SET categoryIds = :categoryIds"
    )
    suspend fun upsertProduct(id: UUID, categoryIds: Array<UUID>)
}

/**
 * Repository for [LocalCopyProductVariantEntity]s
 */
interface LocalCopyProductVariantRepository : CoroutineCrudRepository<LocalCopyProductVariantEntity, UUID> {

    /** Creates the copy of a product variant; an existing one is kept. */
    @Modifying
    @Query("INSERT INTO LocalCopyProductVariantEntity (id, productId) VALUES (:id, :productId) ON CONFLICT DO NOTHING")
    suspend fun createProductVariant(id: UUID, productId: UUID)

    /** The product variants [ids] that are in the copy, with their product and its categories. */
    @Query(
        "SELECT v.id, v.productId, p.categoryIds FROM LocalCopyProductVariantEntity v " +
            "LEFT JOIN LocalCopyProductEntity p ON p.id = v.productId WHERE v.id = ANY(:ids)"
    )
    fun findWithCategories(ids: Array<UUID>): Flow<LocalCopyProductVariantCategories>
}

/**
 * Repository for [LocalCopyShipmentMethodEntity]s
 */
interface LocalCopyShipmentMethodRepository : CoroutineCrudRepository<LocalCopyShipmentMethodEntity, UUID> {

    /** Creates or replaces the copy of a shipment method. */
    @Modifying
    @Query(
        "INSERT INTO LocalCopyShipmentMethodEntity (id, baseFees, feesPerItem, feesPerKg) " +
            "VALUES (:id, :baseFees, :feesPerItem, :feesPerKg) ON CONFLICT (id) DO UPDATE SET baseFees = :baseFees, " +
            "feesPerItem = :feesPerItem, feesPerKg = :feesPerKg"
    )
    suspend fun upsertShipmentMethod(id: UUID, baseFees: Long, feesPerItem: Long, feesPerKg: Long)

    /** Marks the copy of a shipment method as archived. */
    @Modifying
    @Query("UPDATE LocalCopyShipmentMethodEntity SET archived = true WHERE id = :id")
    suspend fun archiveShipmentMethod(id: UUID)

    /** The shipment methods [ids] that are in the copy. */
    @Query("SELECT * FROM LocalCopyShipmentMethodEntity WHERE id = ANY(:ids)")
    fun findAllByIds(ids: Array<UUID>): Flow<LocalCopyShipmentMethodEntity>
}

/**
 * Repository for [LocalCopyProductVariantVersionEntity]s
 */
interface LocalCopyProductVariantVersionRepository : CoroutineCrudRepository<LocalCopyProductVariantVersionEntity, UUID> {

    /** Creates the copy of a product variant version's weight; an existing one is kept. */
    @Modifying
    @Query("INSERT INTO LocalCopyProductVariantVersionEntity (id, weight) VALUES (:id, :weight) ON CONFLICT DO NOTHING")
    suspend fun createProductVariantVersion(id: UUID, weight: Double)

    /** The product variant versions [ids] that are in the copy. */
    @Query("SELECT * FROM LocalCopyProductVariantVersionEntity WHERE id = ANY(:ids)")
    fun findAllByIds(ids: Array<UUID>): Flow<LocalCopyProductVariantVersionEntity>
}
