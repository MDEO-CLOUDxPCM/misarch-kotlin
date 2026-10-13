package org.misarch.discount.persistence.repository

import org.misarch.discount.persistence.model.DiscountToCategoryEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.*

/**
 * Repository for [DiscountToCategoryEntity]s
 */
@Repository
interface DiscountToCategoryRepository : CoroutineCrudRepository<DiscountToCategoryEntity, UUID> {

    /**
     * Finds [DiscountToCategoryEntity]s by discount id
     *
     * @param discountId the discount id
     * @return the found [DiscountToCategoryEntity]s
     */
    suspend fun findByDiscountId(discountId: UUID): List<DiscountToCategoryEntity>

    /**
     * Deletes [DiscountToCategoryEntity]s by discount id and category id
     * (the original used the equivalent derived delete query)
     *
     * @param discountId the discount id
     * @param categoryId the category id
     */
    @Modifying
    @Query("DELETE FROM DiscountToCategoryEntity WHERE discountId = :discountId AND categoryId = :categoryId")
    suspend fun deleteByDiscountIdAndCategoryId(@Param("discountId") discountId: UUID, @Param("categoryId") categoryId: UUID)

}
