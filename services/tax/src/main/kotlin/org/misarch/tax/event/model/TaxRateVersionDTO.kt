package org.misarch.tax.event.model

import java.time.OffsetDateTime
import java.util.UUID

/**
 * TaxRateVersion DTO used for events
 *
 * @property id id of the tax rate version
 * @property rate rate of the tax rate version
 * @property version version of the tax rate version
 * @property createdAt creation date of the tax rate version
 * @property taxRateId id of the tax rate
 */
data class TaxRateVersionDTO(
    val id: UUID,
    val rate: Double,
    val version: Int,
    val createdAt: OffsetDateTime,
    val taxRateId: UUID
)
