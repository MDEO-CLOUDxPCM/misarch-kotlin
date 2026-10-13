package org.misarch.catalog.persistence.repository

import org.misarch.catalog.persistence.model.ProductVariantEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.*

/**
 * Repository for [ProductVariantEntity]s
 */
@Repository
interface ProductVariantRepository : CoroutineCrudRepository<ProductVariantEntity, UUID>
