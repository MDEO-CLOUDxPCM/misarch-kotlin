package org.misarch.discount.persistence.repository

import org.misarch.discount.persistence.model.DiscountToCategoryEntity
import org.misarch.discount.persistence.model.DiscountToCategoryKey
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [DiscountToCategoryEntity]s
 */
interface DiscountToCategoryRepository : CoroutineCrudRepository<DiscountToCategoryEntity, DiscountToCategoryKey> {

    /**
     * Finds [DiscountToCategoryEntity]s by discount id
     *
     * @param discountId the discount id
     * @return the found [DiscountToCategoryEntity]s
     */
    suspend fun findByKeyDiscountId(discountId: UUID): List<DiscountToCategoryEntity>

}
