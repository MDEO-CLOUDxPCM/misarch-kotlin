package org.misarch.discount.persistence.repository

import org.misarch.discount.persistence.model.DiscountEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.*

/**
 * Repository for [DiscountEntity]s
 */
interface DiscountRepository : CoroutineCrudRepository<DiscountEntity, UUID>
