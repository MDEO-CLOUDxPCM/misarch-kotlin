package org.misarch.discount.persistence.repository

import org.misarch.discount.persistence.model.ProductToCategoryEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.*

/**
 * Repository for [ProductToCategoryEntity]s
 */
@Repository
interface ProductToCategoryRepository : CoroutineCrudRepository<ProductToCategoryEntity, UUID>
