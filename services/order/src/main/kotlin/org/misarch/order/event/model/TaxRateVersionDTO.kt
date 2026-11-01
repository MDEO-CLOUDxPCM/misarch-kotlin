package org.misarch.order.event.model

import java.util.UUID

/**
 * DTO for a created tax rate version (the fields order uses)
 *
 * @property id unique identifier of the tax rate version
 * @property rate the rate of the version
 * @property version the version number
 * @property taxRateId the id of the tax rate
 */
data class TaxRateVersionDTO(
    val id: UUID,
    val rate: Double,
    val version: Long,
    val taxRateId: UUID
)
