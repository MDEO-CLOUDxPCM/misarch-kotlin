package org.misarch.order.persistence.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Entity for tax rates with their current version (replica, fed by `tax/tax-rate-version/created`)
 *
 * @property currentVersionId the id of the current tax rate version
 * @property currentVersionRate the rate of the current version
 * @property currentVersionVersion the version number of the current version
 * @property id unique identifier of the tax rate
 */
@Table
class TaxRateEntity(
    val currentVersionId: UUID,
    val currentVersionRate: Double,
    val currentVersionVersion: Long,
    @Id
    val id: UUID
)
