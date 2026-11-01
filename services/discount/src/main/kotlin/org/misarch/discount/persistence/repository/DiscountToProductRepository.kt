package org.misarch.discount.persistence.repository

import org.misarch.discount.persistence.model.DiscountToProductEntity
import org.misarch.discount.persistence.model.DiscountToProductKey
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [DiscountToProductEntity]s
 */
interface DiscountToProductRepository : CoroutineCrudRepository<DiscountToProductEntity, DiscountToProductKey> {

    /**
     * Finds [DiscountToProductEntity]s by discount id
     *
     * @param discountId the discount id
     * @return the found [DiscountToProductEntity]s
     */
    suspend fun findByKeyDiscountId(discountId: UUID): List<DiscountToProductEntity>

}
