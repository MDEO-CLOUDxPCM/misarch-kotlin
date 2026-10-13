package org.misarch.discount.persistence.repository

import org.misarch.discount.persistence.model.DiscountToProductEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.*

/**
 * Repository for [DiscountToProductEntity]s
 */
@Repository
interface DiscountToProductRepository : CoroutineCrudRepository<DiscountToProductEntity, UUID> {

    /**
     * Finds [DiscountToProductEntity]s by discount id
     *
     * @param discountId the discount id
     * @return the found [DiscountToProductEntity]s
     */
    suspend fun findByDiscountId(discountId: UUID): List<DiscountToProductEntity>

    /**
     * Deletes [DiscountToProductEntity]s by discount id and product id
     * (the original used the equivalent derived delete query)
     *
     * @param discountId the discount id
     * @param productId the product id
     */
    @Modifying
    @Query("DELETE FROM DiscountToProductEntity WHERE discountId = :discountId AND productId = :productId")
    suspend fun deleteByDiscountIdAndProductId(@Param("discountId") discountId: UUID, @Param("productId") productId: UUID)

}
