package org.misarch.catalog.service

import org.misarch.catalog.event.model.CreateTaxRateDTO
import org.misarch.catalog.persistence.model.TaxRateEntity
import org.misarch.catalog.persistence.repository.TaxRateRepository
import org.springframework.stereotype.Service

/**
 * Service for [TaxRateEntity]s
 *
 * @property repository the repository of the service's entity
 */
@Service
class TaxRateService(
    private val repository: TaxRateRepository
) {

    /**
     * Registers a TaxRate (replica, from `tax/tax-rate/created`). Idempotent, so a redelivered event is acknowledged.
     *
     * @param taxRateDTO the TaxRate to register
     */
    suspend fun registerTaxRate(taxRateDTO: CreateTaxRateDTO) {
        repository.createTaxRate(taxRateDTO.id)
    }

}
