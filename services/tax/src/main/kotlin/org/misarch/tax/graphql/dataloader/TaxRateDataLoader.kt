package org.misarch.tax.graphql.dataloader

import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.tax.graphql.model.TaxRate
import org.misarch.tax.persistence.model.TaxRateEntity
import org.misarch.tax.persistence.repository.TaxRateRepository
import org.springframework.stereotype.Component

/**
 * Data loader for [TaxRate]s. An unknown id fails only its own key, not the whole batch.
 */
@Component
class TaxRateDataLoader(
    repository: TaxRateRepository
) : IdDataLoader<TaxRate, TaxRateEntity>(repository) {
}
