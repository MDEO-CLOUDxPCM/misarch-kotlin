package org.misarch.invoice.event.model

import java.util.UUID

/**
 * DTO of an archived user address
 *
 * @property id unique identifier of the address
 * @property userId the id of the user
 */
data class ArchivedUserAddressDTO(
    val id: UUID,
    val userId: UUID
)
