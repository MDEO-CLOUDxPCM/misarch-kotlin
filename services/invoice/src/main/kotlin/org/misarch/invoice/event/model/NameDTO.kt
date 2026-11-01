package org.misarch.invoice.event.model

/**
 * DTO of the name of an addressee
 *
 * @property firstName the first name
 * @property lastName the last name
 */
data class NameDTO(
    val firstName: String?,
    val lastName: String?
)
