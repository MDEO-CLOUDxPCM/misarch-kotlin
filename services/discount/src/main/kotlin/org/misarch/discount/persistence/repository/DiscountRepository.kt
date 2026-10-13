package org.misarch.discount.persistence.repository

import org.misarch.discount.persistence.model.DiscountEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.*

/**
 * Repository for [DiscountEntity]s
 */
@Repository
interface DiscountRepository : CoroutineCrudRepository<DiscountEntity, UUID>
