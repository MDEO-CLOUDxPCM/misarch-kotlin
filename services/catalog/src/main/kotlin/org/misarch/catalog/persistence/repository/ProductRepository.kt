package org.misarch.catalog.persistence.repository

import org.misarch.catalog.persistence.model.ProductEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.*

/**
 * Repository for [ProductEntity]s
 */
interface ProductRepository : CoroutineCrudRepository<ProductEntity, UUID>
