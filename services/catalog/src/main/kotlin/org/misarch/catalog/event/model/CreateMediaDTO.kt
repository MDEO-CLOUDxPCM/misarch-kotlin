package org.misarch.catalog.event.model

import java.util.UUID

/**
 * Event for when a Media is crated
 */
data class CreateMediaDTO(
    val id: UUID
)