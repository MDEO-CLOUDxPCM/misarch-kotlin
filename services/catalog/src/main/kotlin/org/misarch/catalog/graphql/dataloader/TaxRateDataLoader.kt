package org.misarch.catalog.graphql.dataloader

import org.misarch.catalog.graphql.model.TaxRate
import org.misarch.catalog.persistence.model.TaxRateEntity
import org.misarch.catalog.persistence.repository.TaxRateRepository
import org.misarch.common.graphql.dataloader.IdDataLoader
import org.springframework.stereotype.Component

/**
 * Data loader for [TaxRate]s. An unknown id fails only its own key, not the whole batch.
 */
@Component
class TaxRateDataLoader(
    repository: TaxRateRepository
) : IdDataLoader<TaxRate, TaxRateEntity>(repository) {
}
