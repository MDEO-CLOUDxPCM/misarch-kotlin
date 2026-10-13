package org.misarch.catalog.persistence.repository

import org.misarch.catalog.persistence.model.CategoryEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.*

/**
 * Repository for [CategoryEntity]s
 */
@Repository
interface CategoryRepository : CoroutineCrudRepository<CategoryEntity, UUID>
