package org.misarch.order.event.model

import java.util.UUID

/**
 * DTO for a created or archived user address (only the ids are used)
 *
 * @property id unique identifier of the address
 * @property userId the id of the user the address belongs to
 */
data class UserAddressDTO(
    val id: UUID,
    val userId: UUID
)
