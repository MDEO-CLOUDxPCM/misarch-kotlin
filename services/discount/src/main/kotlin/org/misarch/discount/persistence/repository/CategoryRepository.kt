package org.misarch.discount.persistence.repository

import org.misarch.discount.persistence.model.CategoryEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.*

/**
 * Repository for [CategoryEntity]s
 */
interface CategoryRepository : CoroutineCrudRepository<CategoryEntity, UUID> {

    /**
     * Creates a category (replica, fed by an event). A duplicate id fails (primary key), like in the original.
     *
     * @param id the id of the category
     */
    @Modifying
    @Query("INSERT INTO CategoryEntity (id) VALUES (:id) ON CONFLICT (id) DO NOTHING")
    suspend fun createCategory(
        id: UUID
    )

}
