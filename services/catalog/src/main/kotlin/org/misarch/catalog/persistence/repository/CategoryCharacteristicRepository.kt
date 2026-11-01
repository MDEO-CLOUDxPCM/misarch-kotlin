package org.misarch.catalog.persistence.repository

import org.misarch.catalog.persistence.model.CategoryCharacteristicEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [CategoryCharacteristicEntity]s
 */
interface CategoryCharacteristicRepository : CoroutineCrudRepository<CategoryCharacteristicEntity, UUID>
