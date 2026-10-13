package org.misarch.catalog.persistence.repository

import org.misarch.catalog.persistence.model.ProductEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.*

/**
 * Repository for [ProductEntity]s
 */
@Repository
interface ProductRepository : CoroutineCrudRepository<ProductEntity, UUID>
