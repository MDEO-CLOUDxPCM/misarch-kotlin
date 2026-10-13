package org.misarch.tax.persistence.repository

import org.misarch.tax.persistence.model.TaxRateEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Repository for [TaxRateEntity]s
 */
@Repository
interface TaxRateRepository : CoroutineCrudRepository<TaxRateEntity, UUID>
