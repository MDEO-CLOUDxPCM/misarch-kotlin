package org.misarch.catalog.persistence.repository

import org.misarch.catalog.persistence.model.ProductVariantVersionToMediaEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.*

/**
 * Repository for [ProductVariantVersionToMediaEntity]s
 */
@Repository
interface ProductVariantVersionToMediaRepository : CoroutineCrudRepository<ProductVariantVersionToMediaEntity, UUID>
