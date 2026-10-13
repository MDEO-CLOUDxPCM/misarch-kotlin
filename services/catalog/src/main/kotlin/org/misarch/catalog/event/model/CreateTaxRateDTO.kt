package org.misarch.catalog.event.model

import java.util.UUID

/**
 * Event for when a TaxRate is crated
 */
data class CreateTaxRateDTO(
    val id: UUID
)