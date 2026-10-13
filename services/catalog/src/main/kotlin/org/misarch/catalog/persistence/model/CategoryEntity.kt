package org.misarch.catalog.persistence.model

import org.misarch.catalog.event.model.CategoryDTO
import org.misarch.catalog.graphql.model.Category
import org.misarch.common.persistence.BaseEntity
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.*

/**
 * Entity for categories
 *
 * @property name name of the category
 * @property description description of the category
 * @property id unique identifier of the category
 */
@Table
class CategoryEntity(
    val name: String,
    val description: String,
    @Id
    override val id: UUID?
) : BaseEntity<Category> {

    override fun toDTO(): Category {
        return Category(
            id = id!!,
            name = name,
            description = description
        )
    }

    /**
     * Convert the entity to a DTO for events
     *
     * @return the DTO
     */
    fun toEventDTO(): CategoryDTO {
        return CategoryDTO(
            id = id!!,
            name = name,
            description = description
        )
    }

}