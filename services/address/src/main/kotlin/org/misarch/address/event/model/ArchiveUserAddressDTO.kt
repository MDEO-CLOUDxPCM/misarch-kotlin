package org.misarch.address.event.model

import java.util.UUID

/**
 * DTO for archive user address events
 *
 * @property id id of the archived address
 * @property userId the id of the user owning the address
 */
data class ArchiveUserAddressDTO(
    val id: UUID,
    val userId: UUID
)
