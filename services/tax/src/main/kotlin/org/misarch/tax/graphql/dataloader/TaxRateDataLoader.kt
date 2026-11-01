package org.misarch.tax.graphql.dataloader

import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.tax.graphql.model.TaxRate
import org.misarch.tax.persistence.model.TaxRateEntity
import org.misarch.tax.persistence.repository.TaxRateRepository
import org.springframework.stereotype.Component

/**
 * Data loader for [TaxRate]s
 *
 * @param repository repository for [TaxRateEntity]s
 */
@Component
class TaxRateDataLoader(
    repository: TaxRateRepository
) : IdDataLoader<TaxRate, TaxRateEntity>(repository) {

    /** An unknown id fails only its own key (`… : null`), not the whole batch (docs/fixes/tax.md). */
}
