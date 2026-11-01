package org.misarch.discount.service

import org.misarch.discount.event.model.CategoryDTO
import org.misarch.discount.persistence.model.CategoryEntity
import org.misarch.discount.persistence.repository.CategoryRepository
import org.springframework.stereotype.Service

/**
 * Service for [CategoryEntity]s
 */
@Service
class CategoryService(
    private val repository: CategoryRepository
) {

    /**
     * Registers a category
     *
     * @param categoryDTO the category to register
     */
    suspend fun registerCategory(categoryDTO: CategoryDTO) {
        repository.createCategory(categoryDTO.id)
    }

}
