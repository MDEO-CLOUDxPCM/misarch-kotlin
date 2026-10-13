package org.misarch.catalog.graphql.dataloader

import org.misarch.catalog.graphql.model.TaxRate
import org.misarch.catalog.persistence.model.TaxRateEntity
import org.misarch.catalog.persistence.repository.TaxRateRepository
import org.misarch.common.graphql.dataloader.IdDataLoader
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
    /** FIXED (docs/fixes/catalog.md): an unknown id fails only its own key (was: the whole batch, NPE). */
    override val failMissingKeysIndividually: Boolean = true
}
