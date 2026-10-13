package org.misarch.catalog.service

import org.misarch.catalog.event.model.CreateTaxRateDTO
import org.misarch.catalog.persistence.model.TaxRateEntity
import org.misarch.catalog.persistence.repository.TaxRateRepository
import org.springframework.stereotype.Service

/**
 * Service for [TaxRateEntity]s
 *
 * @param repository the provided repository
 */
@Service
class TaxRateService(
    repository: TaxRateRepository
) : BaseService<TaxRateEntity, TaxRateRepository>(repository) {

    /**
     * Registers a TaxRate (replica, from `tax/tax-rate/created`). A duplicate id fails (primary key), so Dapr
     * redelivers, like in the original service.
     *
     * @param taxRateDTO the TaxRate to register
     */
    suspend fun registerTaxRate(taxRateDTO: CreateTaxRateDTO) {
        repository.createTaxRate(taxRateDTO.id)
    }

}
