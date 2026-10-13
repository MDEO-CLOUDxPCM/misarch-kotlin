package org.misarch.catalog.event.model

import java.util.*

/**
 * Category DTO used for events
 *
 * @property id unique identifier of the category
 * @property name name of the category
 * @property description description of the category
 */
data class CategoryDTO(
    val id: UUID,
    val name: String,
    val description: String,
)