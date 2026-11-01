package org.misarch.discount.persistence.repository

import org.misarch.discount.persistence.model.DiscountToProductVariantEntity
import org.misarch.discount.persistence.model.DiscountToProductVariantKey
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [DiscountToProductVariantEntity]s
 */
interface DiscountToProductVariantRepository : CoroutineCrudRepository<DiscountToProductVariantEntity, DiscountToProductVariantKey> {

    /**
     * Finds [DiscountToProductVariantEntity]s by discount id
     *
     * @param discountId the discount id
     * @return the found [DiscountToProductVariantEntity]s
     */
    suspend fun findByKeyDiscountId(discountId: UUID): List<DiscountToProductVariantEntity>

}
