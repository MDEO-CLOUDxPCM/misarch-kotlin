package org.misarch.tax.event.model

import java.util.UUID

/**
 * TaxRate DTO used for events
 *
 * @property id id of the tax rate
 * @property name name of the tax rate
 * @property description description of the tax rate
 * @property currentVersionId id of the current version of the tax rate
 */
data class TaxRateDTO(
    val id: UUID,
    val name: String,
    val description: String,
    val currentVersionId: UUID
)
