package org.misarch.discount.persistence.repository

import org.misarch.discount.persistence.model.DiscountToProductVariantEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.*

/**
 * Repository for [DiscountToProductVariantEntity]s
 */
@Repository
interface DiscountToProductVariantRepository : CoroutineCrudRepository<DiscountToProductVariantEntity, UUID> {

    /**
     * Finds [DiscountToProductVariantEntity]s by discount id
     *
     * @param discountId the discount id
     * @return the found [DiscountToProductVariantEntity]s
     */
    suspend fun findByDiscountId(discountId: UUID): List<DiscountToProductVariantEntity>

    /**
     * Deletes [DiscountToProductVariantEntity]s by discount id and product variant id
     * (the original used the equivalent derived delete query)
     *
     * @param discountId the discount id
     * @param productVariantId the product variant id
     */
    @Modifying
    @Query("DELETE FROM DiscountToProductVariantEntity WHERE discountId = :discountId AND productVariantId = :productVariantId")
    suspend fun deleteByDiscountIdAndProductVariantId(@Param("discountId") discountId: UUID, @Param("productVariantId") productVariantId: UUID)

}
