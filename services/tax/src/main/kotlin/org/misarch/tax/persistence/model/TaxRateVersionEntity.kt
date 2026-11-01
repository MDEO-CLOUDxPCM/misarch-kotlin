package org.misarch.tax.persistence.model

import org.misarch.common.persistence.BaseEntity
import org.misarch.common.persistence.requiredId
import org.misarch.tax.event.model.TaxRateVersionDTO
import org.misarch.tax.graphql.model.TaxRateVersion
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Entity for tax rate versions (table `TaxRateVersionEntity`)
 *
 * @property rate the rate
 * @property version the version number (1, 2, ... per tax rate)
 * @property createdAt creation time
 * @property taxRateId id of the tax rate
 * @property id unique identifier of the tax rate version
 */
@Table
class TaxRateVersionEntity(
    val rate: Double,
    val version: Int,
    val createdAt: OffsetDateTime,
    val taxRateId: UUID,
    @Id
    override val id: UUID? = null
) : BaseEntity<TaxRateVersion> {

    override fun toDTO(): TaxRateVersion {
        return TaxRateVersion(
            id = requiredId,
            rate = rate,
            createdAt = createdAt,
            version = version,
            taxRateId = taxRateId
        )
    }

    /**
     * Converts the entity to an event DTO
     *
     * @return the event DTO
     */
    fun toEventDTO(): TaxRateVersionDTO {
        return TaxRateVersionDTO(
            id = requiredId,
            rate = rate,
            createdAt = createdAt,
            version = version,
            taxRateId = taxRateId
        )
    }

}
