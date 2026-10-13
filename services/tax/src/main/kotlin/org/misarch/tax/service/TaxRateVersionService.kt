package org.misarch.tax.service

import org.misarch.common.event.EventPublisher
import org.misarch.tax.event.TaxEvents
import org.misarch.tax.graphql.input.CreateTaxRateVersionInput
import org.misarch.tax.graphql.input.TaxRateVersionInput
import org.misarch.tax.persistence.model.TaxRateVersionEntity
import org.misarch.tax.persistence.repository.TaxRateRepository
import org.misarch.tax.persistence.repository.TaxRateVersionRepository
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Service for [TaxRateVersionEntity]s
 *
 * @param repository the provided repository
 * @param taxRateRepository the tax rate repository
 * @param eventPublisher the event publisher
 */
@Service
class TaxRateVersionService(
    private val repository: TaxRateVersionRepository,
    private val taxRateRepository: TaxRateRepository,
    private val eventPublisher: EventPublisher
) {

    /**
     * Creates a new version of a tax rate, makes it the current version and publishes
     * `tax/tax-rate-version/created` (not transactional, like the original)
     *
     * @param input defines the version to create
     * @return the created version
     */
    suspend fun createTaxRateVersion(
        input: CreateTaxRateVersionInput
    ): TaxRateVersionEntity {
        if (!taxRateRepository.existsById(input.taxRateId)) {
            throw IllegalArgumentException("TaxRate with id ${input.taxRateId} does not exist.")
        }
        val taxRateVersion = createTaxRateVersionInternal(input, input.taxRateId)
        val taxRate = taxRateRepository.findById(input.taxRateId) ?: throw NoSuchElementException()
        taxRate.currentVersionId = taxRateVersion.id
        taxRateRepository.save(taxRate)
        eventPublisher.publishEvent(TaxEvents.TAX_RATE_VERSION_CREATED, taxRateVersion.toEventDTO())
        return taxRateVersion
    }

    /**
     * Inserts a new version (`MAX(version) + 1`, or 1) without updating the tax rate or publishing an event
     *
     * @param input defines the rate
     * @param taxRateId the id of the tax rate
     * @return the created version
     */
    suspend fun createTaxRateVersionInternal(
        input: TaxRateVersionInput, taxRateId: UUID
    ): TaxRateVersionEntity {
        val version = repository.findMaxVersionByProductVariantId(taxRateId)?.plus(1) ?: 1
        val taxRateVersion = TaxRateVersionEntity(
            rate = input.rate,
            version = version,
            createdAt = OffsetDateTime.now(),
            taxRateId = taxRateId,
            id = null
        )
        return repository.save(taxRateVersion)
    }

}
