package org.misarch.order.service

import org.misarch.order.event.model.TaxRateVersionDTO
import org.misarch.order.persistence.model.TaxRateEntity
import org.misarch.order.persistence.repository.TaxRateRepository
import org.springframework.stereotype.Service

/**
 * Service for [TaxRateEntity]s
 *
 * @param repository the repository for [TaxRateEntity]s
 */
@Service
class TaxRateService(
    private val repository: TaxRateRepository
) {

    /**
     * Registers a new tax rate version as the current version of its tax rate
     *
     * @param taxRateVersionDTO the created tax rate version
     */
    suspend fun registerTaxRateVersion(taxRateVersionDTO: TaxRateVersionDTO) {
        repository.upsertCurrentVersion(
            taxRateVersionDTO.taxRateId,
            taxRateVersionDTO.id,
            taxRateVersionDTO.rate,
            taxRateVersionDTO.version
        )
    }

}
