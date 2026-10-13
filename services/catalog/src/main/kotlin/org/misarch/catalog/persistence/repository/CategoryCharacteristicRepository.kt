package org.misarch.catalog.persistence.repository

import org.misarch.catalog.persistence.model.CategoryCharacteristicEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.*

/**
 * Repository for [CategoryCharacteristicEntity]s
 */
@Repository
interface CategoryCharacteristicRepository : CoroutineCrudRepository<CategoryCharacteristicEntity, UUID>
