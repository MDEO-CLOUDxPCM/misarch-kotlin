package org.misarch.tax.service

import org.misarch.common.event.EventPublisher
import org.misarch.tax.event.TaxEvents
import org.misarch.tax.graphql.input.CreateTaxRateInput
import org.misarch.tax.graphql.input.UpdateTaxRateInput
import org.misarch.tax.persistence.model.TaxRateEntity
import org.misarch.tax.persistence.repository.TaxRateRepository
import org.springframework.stereotype.Service

/**
 * Service for [TaxRateEntity]s
 *
 * @param repository the provided repository
 * @param taxRateVersionService used to create the initial version
 * @param eventPublisher the event publisher
 */
@Service
class TaxRateService(
    private val repository: TaxRateRepository,
    private val taxRateVersionService: TaxRateVersionService,
    private val eventPublisher: EventPublisher
) {

    /**
     * Creates a tax rate with its initial version (insert the tax rate, insert the version, update the tax rate's
     * current version; all in the mutation's transaction), then publishes `tax/tax-rate/created` and
     * `tax/tax-rate-version/created`.
     *
     * @param input defines the tax rate to create
     * @return the created tax rate
     */
    suspend fun createTaxRate(input: CreateTaxRateInput): TaxRateEntity {
        val taxRate = TaxRateEntity(
            name = input.name,
            description = input.description,
            currentVersionId = null,
            id = null
        )
        val savedTaxRate = repository.save(taxRate)
        val taxRateVersion = taxRateVersionService.createTaxRateVersionInternal(input.initialVersion, savedTaxRate.id!!)
        savedTaxRate.currentVersionId = taxRateVersion.id
        val newSavedTaxRate = repository.save(savedTaxRate)
        eventPublisher.publishEvent(TaxEvents.TAX_RATE_CREATED, newSavedTaxRate.toEventDTO())
        eventPublisher.publishEvent(TaxEvents.TAX_RATE_VERSION_CREATED, taxRateVersion.toEventDTO())
        return newSavedTaxRate
    }

    /**
     * Updates the name and/or description of a tax rate (no event, like the original)
     *
     * @param input defines the tax rate and the new values
     * @return the updated tax rate
     */
    suspend fun updateTaxRate(input: UpdateTaxRateInput): TaxRateEntity {
        // original: findById(...).awaitSingle() -> NoSuchElementException without message for an unknown id
        val taxRate = repository.findById(input.id) ?: throw NoSuchElementException()
        if (input.name != null) {
            taxRate.name = input.name
        }
        if (input.description != null) {
            taxRate.description = input.description
        }
        return repository.save(taxRate)
    }

}
