package org.misarch.tax.persistence.model

import org.misarch.common.persistence.BaseEntity
import org.misarch.common.persistence.requiredId
import org.misarch.tax.event.model.TaxRateDTO
import org.misarch.tax.graphql.model.TaxRate
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Entity for tax rates (table `TaxRateEntity`)
 *
 * @property name name of the tax rate
 * @property description description of the tax rate
 * @property currentVersionId id of the current version (null only between the inserts of `createTaxRate`)
 * @property id unique identifier of the tax rate
 */
@Table
class TaxRateEntity(
    var name: String,
    var description: String,
    var currentVersionId: UUID?,
    @Id
    override val id: UUID? = null
) : BaseEntity<TaxRate> {

    override fun toDTO(): TaxRate {
        return TaxRate(
            id = requiredId,
            name = name,
            description = description,
            currentVersionId = currentVersionId!!
        )
    }

    /**
     * Converts the entity to an event DTO
     *
     * @return the event DTO
     */
    fun toEventDTO(): TaxRateDTO {
        return TaxRateDTO(
            id = requiredId,
            name = name,
            description = description,
            currentVersionId = currentVersionId!!
        )
    }

}
