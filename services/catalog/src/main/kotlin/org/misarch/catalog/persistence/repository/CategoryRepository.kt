package org.misarch.catalog.persistence.repository

import org.misarch.catalog.persistence.model.CategoryEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [CategoryEntity]s
 */
interface CategoryRepository : CoroutineCrudRepository<CategoryEntity, UUID>
